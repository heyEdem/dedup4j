package com.edem.dedup4j.service;

import com.edem.dedup4j.core.hash.ContentHash;
import com.edem.dedup4j.core.model.BlobReference;
import com.edem.dedup4j.core.model.StoreBlobCommand;
import com.edem.dedup4j.history.UploadHistory;
import com.edem.dedup4j.historystore.UploadContribution;
import com.edem.dedup4j.jpa.AssetContent;
import com.edem.dedup4j.jpa.AssetContentRepository;
import com.edem.dedup4j.jpa.DuplicateContentIdentityException;
import com.edem.dedup4j.jpa.ReferenceCountService;
import com.edem.dedup4j.observability.Dedup4jMetrics;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Changes reference counts inside the caller's transaction, so an application can commit its own
 * reference row and the count change together: the writer contract
 * {@link com.edem.dedup4j.reconcile.CoordinatedRepair} relies on.
 *
 * <p>Every call joins the transaction run by dedup4j's transaction manager; a call outside it, or in
 * a read-only one, throws {@code IllegalTransactionStateException} or {@link IllegalStateException}.
 * An upload is read and validated before any SQL, so a rejected upload leaves the transaction usable;
 * any later failure marks it rollback-only. Rolling back undoes the count change, but an object
 * written for brand-new content stays in storage with no row pointing to it.</p>
 *
 * <ul>
 *   <li>Uploads that race to create the same content: the loser gets a
 *   {@link ContentIdentityConflictException} and retries its whole transaction.</li>
 *   <li>Upload history is appended just before commit, after the transaction's own changes are
 *   flushed, so its shared lock is the last one taken and is held only through commit. While history
 *   is persistent, the transaction must run at READ COMMITTED (the PostgreSQL and H2 default).</li>
 *   <li>Content released to zero has its object deleted after commit, in a new transaction, unless a
 *   later upload restored it. A rollback deletes nothing.</li>
 *   <li>Failures are recorded in history after the transaction completes; meters only after commit.</li>
 *   <li>Locks are held until the caller commits; a transaction touching several contents should take
 *   them in a consistent order, or retry on deadlock.</li>
 * </ul>
 */
public final class TransactionalBlobReferences {

    private static final Logger LOGGER = LoggerFactory.getLogger(TransactionalBlobReferences.class);
    private static final String UPLOAD = "upload";
    private static final String DELETE = "delete";

    private final DefaultBlobDeduplicationService delegate;
    private final AssetContentRepository repository;
    private final ReferenceCountService referenceCountService;
    private final EntityManager entityManager;
    private final TransactionTemplate joined;
    private final TransactionTemplate deletes;
    private final UploadHistory history;
    private final Dedup4jMetrics metrics;

    public TransactionalBlobReferences(
            DefaultBlobDeduplicationService delegate,
            AssetContentRepository repository,
            ReferenceCountService referenceCountService,
            EntityManager entityManager,
            PlatformTransactionManager transactionManager,
            UploadHistory history,
            Dedup4jMetrics metrics
    ) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.referenceCountService = Objects.requireNonNull(
                referenceCountService, "referenceCountService must not be null");
        this.entityManager = Objects.requireNonNull(entityManager, "entityManager must not be null");
        Objects.requireNonNull(transactionManager, "transactionManager must not be null");
        this.joined = new TransactionTemplate(transactionManager);
        this.joined.setPropagationBehavior(TransactionDefinition.PROPAGATION_MANDATORY);
        this.deletes = new TransactionTemplate(transactionManager);
        this.deletes.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.history = Objects.requireNonNull(history, "history must not be null");
        this.metrics = Objects.requireNonNull(metrics, "metrics must not be null");
    }

    /**
     * Stores or retains the upload's content in the caller's transaction.
     *
     * @throws ContentIdentityConflictException when a concurrent transaction created the same content first
     */
    public BlobReference store(StoreBlobCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        requireWritableTransaction();
        if (history.persistent()) {
            requireReadCommitted();
        }
        AtomicReference<String> contentHash = new AtomicReference<>();
        try {
            byte[] bytes = delegate.buffer(command);
            StoreOutcome outcome;
            try {
                outcome = Objects.requireNonNull(joined.execute(status ->
                        delegate.storeBuffered(command, bytes, hash -> contentHash.set(hash.hash()))));
            } catch (DuplicateContentIdentityException race) {
                throw new ContentIdentityConflictException(
                        race.getHashAlgorithm(), race.getContentHash(), race.getSizeBytes(), race);
            }
            TransactionSynchronizationManager.registerSynchronization(new CompletedUpload(outcome));
            return outcome.reference();
        } catch (RuntimeException failure) {
            recordAfterCompletion(UPLOAD, contentHash.get(), null, failure);
            throw failure;
        }
    }

    /**
     * Adds a reference in the caller's transaction.
     *
     * @throws com.edem.dedup4j.core.exception.ContentNotFoundException when the content is missing or
     *                                                                   released; store its bytes instead
     */
    public void retain(UUID assetContentId) {
        Objects.requireNonNull(assetContentId, "assetContentId must not be null");
        requireWritableTransaction();
        joined.executeWithoutResult(status -> referenceCountService.retain(assetContentId));
    }

    /** Drops a reference in the caller's transaction; the object of a final reference is deleted after commit. */
    public void release(UUID assetContentId) {
        Objects.requireNonNull(assetContentId, "assetContentId must not be null");
        requireWritableTransaction();
        Optional<String> releasedKey = Objects.requireNonNull(joined.execute(status ->
                referenceCountService.release(assetContentId)
                        ? repository.findById(assetContentId).map(AssetContent::getObjectKey)
                        : Optional.<String>empty()));
        releasedKey.ifPresent(objectKey -> TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        deleteReleased(assetContentId, objectKey);
                    }
                }));
    }

    /** The release has committed; a failure leaves the object for {@code ReleasedObjectCleanup} and is never thrown. */
    private void deleteReleased(UUID assetContentId, String objectKey) {
        try {
            deletes.executeWithoutResult(status -> delegate.deleteReleased(assetContentId));
        } catch (RuntimeException failure) {
            LOGGER.warn(
                    "event=blob.cleanup.failed contentId={} objectKey={} errorType={} failureMessage={} "
                            + "detail=\"reference released; object left in storage\"",
                    assetContentId, objectKey, failure.getClass().getSimpleName(), failure.getMessage(), failure);
            recordGuarded(DELETE, null, objectKey, failure);
        }
    }

    private static void requireWritableTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("TransactionalBlobReferences needs a read-write transaction");
        }
    }

    /**
     * The upload ledger's gapless sequence needs READ COMMITTED; a joined call cannot change the
     * caller's isolation. A transaction left at the database default is accepted, as elsewhere.
     */
    private static void requireReadCommitted() {
        Integer isolation = TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
        if (isolation != null && isolation != TransactionDefinition.ISOLATION_READ_COMMITTED) {
            throw new IllegalStateException("TransactionalBlobReferences.store needs a READ COMMITTED transaction "
                    + "while upload history is persistent");
        }
    }

    /** Waits for the transaction to end, so recording never waits on locks it still holds. */
    private void recordAfterCompletion(String operation, String contentHash, String objectKey, RuntimeException failure) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            recordGuarded(operation, contentHash, objectKey, failure);
            return;
        }
        LOGGER.warn("event=blob.upload.failed hashPrefix={} errorType={} failureMessage={}",
                contentHash == null ? null : contentHash.substring(0, Math.min(12, contentHash.length())),
                failure.getClass().getSimpleName(), failure.getMessage());
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                recordGuarded(operation, contentHash, objectKey, failure);
            }
        });
    }

    private void recordGuarded(String operation, String contentHash, String objectKey, RuntimeException failure) {
        try {
            history.recordFailure(operation, contentHash, objectKey, failure);
        } catch (RuntimeException recordingFailure) {
            LOGGER.warn(
                    "event=history.failure.unrecorded operation={} errorType={} failureMessage={}",
                    operation, recordingFailure.getClass().getSimpleName(), recordingFailure.getMessage(),
                    recordingFailure);
        }
    }

    /** Appends the upload to history last, just before commit, and records its meters after commit. */
    private final class CompletedUpload implements TransactionSynchronization {

        private final StoreOutcome outcome;
        private RuntimeException appendFailure;

        private CompletedUpload(StoreOutcome outcome) {
            this.outcome = outcome;
        }

        @Override
        public void beforeCommit(boolean readOnly) {
            if (!history.persistent()) {
                return;
            }
            BlobReference reference = outcome.reference();
            ContentHash hash = reference.contentHash();
            try {
                // Takes the transaction's remaining row locks before the history lock, never after.
                entityManager.flush();
                history.recordUpload(UploadContribution.completed(reference.assetContentId(), hash.algorithm(),
                        hash.hash(), hash.sizeBytes(), reference.duplicate(), outcome.physicalWrite()));
            } catch (RuntimeException failure) {
                appendFailure = failure;
                throw failure;
            }
        }

        @Override
        public void afterCommit() {
            BlobReference reference = outcome.reference();
            try {
                metrics.recordUpload(reference.contentHash().sizeBytes(), reference.duplicate(), outcome.physicalWrite());
            } catch (RuntimeException meterFailure) {
                LOGGER.warn("event=metrics.upload.unrecorded contentId={} errorType={} failureMessage={}",
                        reference.assetContentId(), meterFailure.getClass().getSimpleName(),
                        meterFailure.getMessage(), meterFailure);
            }
        }

        @Override
        public void afterCompletion(int status) {
            if (appendFailure != null) {
                recordGuarded(UPLOAD, outcome.reference().contentHash().hash(), null, appendFailure);
            }
        }
    }
}
