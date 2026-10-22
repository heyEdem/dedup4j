package com.edem.dedup4j.service;

import com.edem.dedup4j.core.exception.BlobStorageException;
import com.edem.dedup4j.core.hash.ContentHash;
import com.edem.dedup4j.core.model.BlobReference;
import com.edem.dedup4j.core.model.BlobLocation;
import com.edem.dedup4j.core.model.StoreBlobCommand;
import com.edem.dedup4j.core.storage.BlobResource;
import com.edem.dedup4j.history.DisabledUploadHistory;
import com.edem.dedup4j.history.UploadHistory;
import com.edem.dedup4j.historystore.UploadContribution;
import com.edem.dedup4j.jpa.AssetContent;
import com.edem.dedup4j.jpa.AssetContentRepository;
import com.edem.dedup4j.jpa.DuplicateContentIdentityException;
import com.edem.dedup4j.jpa.ReferenceCountService;
import com.edem.dedup4j.observability.Dedup4jMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.InvalidIsolationLevelException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Runs each operation in its own transaction and records upload history.
 *
 * <p>A completed upload is appended to the history in the transaction that stores or retains
 * the content, and its meters are recorded only after that transaction commits. A failure is
 * recorded after the failed transaction has ended; the caller always receives the original
 * exception.</p>
 */
public final class SpringTransactionalBlobDeduplicationService implements BlobDeduplicationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(SpringTransactionalBlobDeduplicationService.class);
    private static final String UPLOAD = "upload";
    private static final String DELETE = "delete";

    private final BlobDeduplicationService delegate;
    private final AssetContentRepository repository;
    private final ReferenceCountService referenceCountService;
    private final TransactionTemplate transactions;
    private final AtomicBoolean appendIsolationUnsupported = new AtomicBoolean();
    private volatile TransactionTemplate appends;
    private final TransactionTemplate readOnly;
    private final UploadHistory history;
    private final Dedup4jMetrics metrics;

    public SpringTransactionalBlobDeduplicationService(
            BlobDeduplicationService delegate,
            AssetContentRepository repository,
            ReferenceCountService referenceCountService,
            PlatformTransactionManager transactionManager
    ) {
        this(delegate, repository, referenceCountService, transactionManager,
                new DisabledUploadHistory(), new Dedup4jMetrics(null));
    }

    public SpringTransactionalBlobDeduplicationService(
            BlobDeduplicationService delegate,
            AssetContentRepository repository,
            ReferenceCountService referenceCountService,
            PlatformTransactionManager transactionManager,
            UploadHistory history,
            Dedup4jMetrics metrics
    ) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.referenceCountService = Objects.requireNonNull(
                referenceCountService, "referenceCountService must not be null"
        );
        this.transactions = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager must not be null")
        );
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.readOnly.setReadOnly(true);
        this.history = Objects.requireNonNull(history, "history must not be null");
        this.metrics = Objects.requireNonNull(metrics, "metrics must not be null");
        this.appends = transactions;
        if (history.persistent()) {
            // The history ledger's gapless sequence needs each statement to see rows committed while it waited
            // on the dataset lock; repeatable read would reuse a sequence number and fail the upload.
            this.appends = new TransactionTemplate(transactionManager);
            this.appends.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            this.appends.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        }
    }

    @Override
    public BlobReference store(StoreBlobCommand command) {
        AtomicReference<String> contentHash = new AtomicReference<>();
        try {
            if (delegate instanceof DefaultBlobDeduplicationService service) {
                return storeBuffered(service, command, contentHash);
            }
            try {
                return appendResult(() -> recordCompleted(storeOutcome(command)));
            } catch (DuplicateContentIdentityException race) {
                contentHash.set(race.getContentHash());
                // This request wrote to storage before losing the insert race.
                return appendResult(() -> recordCompleted(new StoreOutcome(retainWinner(race), true)));
            }
        } catch (RuntimeException failure) {
            recordGuarded(UPLOAD, contentHash.get(), null, failure);
            throw failure;
        }
    }

    /**
     * Reads the upload before any transaction opens, so a lost insert race can be retried with the
     * same bytes. The retry finds the winner and, if every reference to it was released in the
     * meantime, writes the object back under the row lock instead of failing.
     */
    private BlobReference storeBuffered(
            DefaultBlobDeduplicationService service,
            StoreBlobCommand command,
            AtomicReference<String> contentHash
    ) {
        byte[] bytes = service.buffer(command);
        Consumer<ContentHash> hashed = hash -> contentHash.set(hash.hash());
        try {
            return appendResult(() -> recordCompleted(service.storeBuffered(command, bytes, hashed)));
        } catch (DuplicateContentIdentityException race) {
            return appendResult(() -> {
                StoreOutcome retry = service.storeBuffered(command, bytes, hashed);
                // This request wrote to storage before losing the insert race.
                return recordCompleted(new StoreOutcome(retry.reference(), true));
            });
        }
    }

    private StoreOutcome storeOutcome(StoreBlobCommand command) {
        BlobReference reference = delegate.store(command);
        return new StoreOutcome(reference, !reference.duplicate());
    }

    private BlobReference recordCompleted(StoreOutcome outcome) {
        BlobReference reference = outcome.reference();
        ContentHash contentHash = reference.contentHash();
        history.recordUpload(UploadContribution.completed(
                reference.assetContentId(),
                contentHash.algorithm(),
                contentHash.hash(),
                contentHash.sizeBytes(),
                reference.duplicate(),
                outcome.physicalWrite()
        ));
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                // The upload has committed; a meter failure must not turn it into a failed upload.
                try {
                    metrics.recordUpload(contentHash.sizeBytes(), reference.duplicate(), outcome.physicalWrite());
                } catch (RuntimeException meterFailure) {
                    LOGGER.warn(
                            "event=metrics.upload.unrecorded contentId={} errorType={} failureMessage={}",
                            reference.assetContentId(),
                            meterFailure.getClass().getSimpleName(),
                            meterFailure.getMessage(),
                            meterFailure
                    );
                }
            }
        });
        return reference;
    }

    private BlobReference retainWinner(DuplicateContentIdentityException race) {
        AssetContent winner = repository.findByIdentity(
                        race.getHashAlgorithm(), race.getContentHash(), race.getSizeBytes()
                )
                .orElseThrow(() -> race);
        referenceCountService.retain(winner.getId());
        return BlobReferences.from(winner, true);
    }

    @Override
    public void retain(UUID assetContentId) {
        transactions.executeWithoutResult(status -> delegate.retain(assetContentId));
    }

    @Override
    public void release(UUID assetContentId) {
        try {
            transactions.executeWithoutResult(status -> delegate.release(assetContentId));
        } catch (RuntimeException failure) {
            if (failure instanceof BlobStorageException) {
                recordGuarded(DELETE, null, objectKeyOf(assetContentId, failure), failure);
            }
            throw failure;
        }
    }

    @Override
    public BlobResource get(UUID assetContentId) {
        return requiredResult(() -> delegate.get(assetContentId));
    }

    @Override
    public BlobLocation location(UUID assetContentId) {
        return requiredResult(() -> delegate.location(assetContentId));
    }

    /**
     * BlobStorageException carries no object key, so it is read from the content row, which the
     * rolled-back release left in place. Runs after the release transaction has ended.
     */
    private String objectKeyOf(UUID assetContentId, RuntimeException failure) {
        try {
            return readOnly.execute(status -> repository.findById(assetContentId)
                    .map(AssetContent::getObjectKey)
                    .orElse(null));
        } catch (RuntimeException lookupFailure) {
            LOGGER.warn(
                    "event=history.failure.key.unresolved contentId={} errorType={} failureMessage={}",
                    assetContentId,
                    lookupFailure.getClass().getSimpleName(),
                    lookupFailure.getMessage(),
                    lookupFailure
            );
            suppress(failure, lookupFailure);
            return null;
        }
    }

    /** Runs after the failed transaction has ended, so it never waits on that transaction's locks. */
    private void recordGuarded(String operation, String contentHash, String objectKey, RuntimeException failure) {
        try {
            history.recordFailure(operation, contentHash, objectKey, failure);
        } catch (RuntimeException recordingFailure) {
            LOGGER.warn(
                    "event=history.failure.unrecorded operation={} errorType={} failureMessage={}",
                    operation,
                    recordingFailure.getClass().getSimpleName(),
                    recordingFailure.getMessage(),
                    recordingFailure
            );
            suppress(failure, recordingFailure);
        }
    }

    private static void suppress(RuntimeException failure, RuntimeException secondary) {
        if (secondary != failure) {
            failure.addSuppressed(secondary);
        }
    }

    private <T> T requiredResult(Supplier<T> action) {
        return Objects.requireNonNull(transactions.execute(status -> action.get()));
    }

    /**
     * Runs a history append at READ COMMITTED. A transaction manager that rejects custom isolation
     * (JTA without allowCustomIsolationLevels, a plain JPA dialect) does so at begin, before the action
     * runs; the append then falls back, once and for good, to the database's default isolation.
     */
    private <T> T appendResult(Supplier<T> action) {
        TransactionTemplate template = appends;
        AtomicBoolean started = new AtomicBoolean();
        try {
            return Objects.requireNonNull(template.execute(status -> {
                started.set(true);
                return action.get();
            }));
        } catch (InvalidIsolationLevelException unsupported) {
            if (started.get() || template == transactions) {
                throw unsupported;
            }
            appends = transactions;
            if (appendIsolationUnsupported.compareAndSet(false, true)) {
                LOGGER.warn(
                        "event=history.isolation.unsupported errorType={} failureMessage={} "
                                + "detail=\"history appends now rely on the database's default isolation\"",
                        unsupported.getClass().getSimpleName(),
                        unsupported.getMessage()
                );
            }
            return requiredResult(action);
        }
    }
}
