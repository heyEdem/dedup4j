package com.edem.dedup4j.cleanup;

import com.edem.dedup4j.core.exception.ContentNotFoundException;
import com.edem.dedup4j.core.hash.ContentHash;
import com.edem.dedup4j.core.hash.ContentHasher;
import com.edem.dedup4j.core.key.ObjectKeyStrategy;
import com.edem.dedup4j.core.storage.BlobResource;
import com.edem.dedup4j.core.storage.ListableBlobStorage;
import com.edem.dedup4j.core.storage.ListedBlob;
import com.edem.dedup4j.history.UploadHistory;
import com.edem.dedup4j.jpa.AssetContent;
import com.edem.dedup4j.jpa.AssetContentMutationService;
import com.edem.dedup4j.jpa.AssetContentRepository;
import com.edem.dedup4j.jpa.DuplicateContentIdentityException;
import com.edem.dedup4j.jpa.ReferenceCountService;
import com.edem.dedup4j.jpa.ReleasedCleanupOutcome;
import com.edem.dedup4j.observability.Dedup4jMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.stream.Stream;

/**
 * Deletes objects under the key prefix that no content row points to, such as an object written for
 * new content whose transaction rolled back, or a put the provider applied after it had timed out.
 *
 * <p>A row cannot be locked before it exists, so an orphan is not deleted directly: an upload could
 * write the same key and commit its row between the check and the delete. Instead each orphan older
 * than the grace period is hashed, and only if its bytes hash to its own key is it adopted as a
 * released row (zero references) in its own transaction. The insert waits for any upload committing
 * the same identity and loses to it; once adopted, the object is deleted under the row's lock by
 * {@link ReferenceCountService#cleanUpReleased}, and an upload of the same bytes restores it rather
 * than inserting. Adopted rows stay at zero references, like other released content.</p>
 *
 * <p>The key prefix must be non-empty and belong to this database alone. Runs refuse an ambient
 * transaction, so an adoption never waits on the caller's own uncommitted upload. An interrupted
 * pass stops before the next object with a {@link CancellationException}.</p>
 */
public final class OrphanedObjectSweep {

    private static final Logger LOGGER = LoggerFactory.getLogger(OrphanedObjectSweep.class);
    private static final String DELETE = "delete";

    private final ListableBlobStorage storage;
    private final ContentHasher hasher;
    private final ObjectKeyStrategy keyStrategy;
    private final AssetContentRepository repository;
    private final AssetContentMutationService mutationService;
    private final ReferenceCountService referenceCountService;
    private final TransactionTemplate transactions;
    private final TransactionTemplate readOnly;
    private final UploadHistory history;
    private final Dedup4jMetrics metrics;
    private final String keyPrefix;
    private final Duration gracePeriod;
    private final Clock clock;

    public OrphanedObjectSweep(
            ListableBlobStorage storage,
            ContentHasher hasher,
            ObjectKeyStrategy keyStrategy,
            AssetContentRepository repository,
            AssetContentMutationService mutationService,
            ReferenceCountService referenceCountService,
            PlatformTransactionManager transactionManager,
            UploadHistory history,
            Dedup4jMetrics metrics,
            String keyPrefix,
            Duration gracePeriod,
            Clock clock
    ) {
        this.storage = Objects.requireNonNull(storage, "storage must not be null");
        this.hasher = Objects.requireNonNull(hasher, "hasher must not be null");
        this.keyStrategy = Objects.requireNonNull(keyStrategy, "keyStrategy must not be null");
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.mutationService = Objects.requireNonNull(mutationService, "mutationService must not be null");
        this.referenceCountService = Objects.requireNonNull(referenceCountService, "referenceCountService must not be null");
        Objects.requireNonNull(transactionManager, "transactionManager must not be null");
        this.history = Objects.requireNonNull(history, "history must not be null");
        this.metrics = Objects.requireNonNull(metrics, "metrics must not be null");
        this.keyPrefix = Objects.requireNonNull(keyPrefix, "keyPrefix must not be null");
        this.gracePeriod = Objects.requireNonNull(gracePeriod, "gracePeriod must not be null");
        if (gracePeriod.isNegative()) {
            throw new IllegalArgumentException("gracePeriod must not be negative");
        }
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.readOnly.setReadOnly(true);
    }

    /**
     * @throws IllegalStateException when the key prefix is empty or a transaction is active
     */
    public OrphanSweepResult run() {
        if (keyPrefix.isBlank()) {
            throw new IllegalStateException("The orphaned object sweep needs dedup4j.storage.key-prefix; "
                    + "without one it would scan the whole bucket, container or storage root");
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Run the orphaned object sweep outside a transaction");
        }
        Instant cutoff = clock.instant().minus(gracePeriod);
        Counts counts = new Counts();
        try (Stream<ListedBlob> listing = storage.list(keyPrefix + "/")) {
            Iterator<ListedBlob> objects = listing.iterator();
            while (objects.hasNext()) {
                ListedBlob object = objects.next();
                if (Thread.currentThread().isInterrupted()) {
                    throw new CancellationException("Orphaned object sweep interrupted after " + counts.listed + " objects");
                }
                counts.listed++;
                if (object.lastModified().isAfter(cutoff)) {
                    counts.recent++;
                    continue;
                }
                try {
                    sweep(object, counts);
                } catch (RuntimeException failure) {
                    counts.failed++;
                    recordFailure(object, failure);
                }
            }
        }
        OrphanSweepResult result = counts.result();
        log(result);
        return result;
    }

    private void sweep(ListedBlob object, Counts counts) {
        if (owned(object.objectKey())) {
            counts.owned++;
            return;
        }
        ContentHash identity;
        try {
            identity = hash(object.objectKey());
        } catch (ContentNotFoundException gone) {
            counts.vanished++;
            return;
        }
        if (identity.sizeBytes() != object.sizeBytes() || !object.objectKey().equals(keyStrategy.generateKey(identity))) {
            counts.unrecognized++;
            LOGGER.warn("event=storage.orphan.unrecognized objectKey={} detail=\"bytes do not match the key; kept\"",
                    object.objectKey());
            return;
        }
        AssetContent adopted;
        try {
            adopted = Objects.requireNonNull(transactions.execute(status -> mutationService.insertReleased(
                    new AssetContent(identity.algorithm(), identity.hash(), identity.sizeBytes(), object.objectKey(),
                            object.provider(), object.bucketOrContainer(), null, null))));
        } catch (DuplicateContentIdentityException rowExists) {
            counts.owned++;
            return;
        }
        ReleasedCleanupOutcome outcome = Objects.requireNonNull(
                transactions.execute(status -> referenceCountService.cleanUpReleased(adopted.getId())));
        switch (outcome) {
            case DELETED -> {
                counts.deleted++;
                metrics.recordOrphanedObjectDeleted();
                LOGGER.info("event=storage.orphan.deleted contentId={} objectKey={} sizeBytes={}",
                        adopted.getId(), object.objectKey(), object.sizeBytes());
            }
            case ABSENT -> counts.vanished++;
            case REFERENCED -> counts.owned++;
        }
    }

    private boolean owned(String objectKey) {
        return Boolean.TRUE.equals(readOnly.execute(status -> repository.existsByObjectKey(objectKey)));
    }

    private ContentHash hash(String objectKey) {
        try (BlobResource resource = storage.get(objectKey)) {
            return hasher.hash(resource.content());
        } catch (IOException failure) {
            throw new UncheckedIOException("Failed to read object: " + objectKey, failure);
        }
    }

    private void recordFailure(ListedBlob object, RuntimeException failure) {
        LOGGER.warn(
                "event=storage.orphan.failed objectKey={} errorType={} failureMessage={} "
                        + "detail=\"object kept; retried next pass\"",
                object.objectKey(),
                failure.getClass().getSimpleName(),
                failure.getMessage(),
                failure
        );
        try {
            history.recordFailure(DELETE, null, object.objectKey(), failure);
        } catch (RuntimeException recordingFailure) {
            LOGGER.warn(
                    "event=history.failure.unrecorded operation={} errorType={} failureMessage={}",
                    DELETE,
                    recordingFailure.getClass().getSimpleName(),
                    recordingFailure.getMessage(),
                    recordingFailure
            );
        }
    }

    private static void log(OrphanSweepResult result) {
        String message = "event=storage.orphan.sweep.completed listed={} recent={} owned={} unrecognized={} "
                + "deleted={} vanished={} failed={}";
        Object[] counts = {result.listed(), result.recent(), result.owned(), result.unrecognized(),
                result.deleted(), result.vanished(), result.failed()};
        if (result.failed() == 0) {
            LOGGER.info(message, counts);
        } else {
            LOGGER.warn(message, counts);
        }
    }

    private static final class Counts {
        private long listed;
        private long recent;
        private long owned;
        private long unrecognized;
        private long deleted;
        private long vanished;
        private long failed;

        private OrphanSweepResult result() {
            return new OrphanSweepResult(listed, recent, owned, unrecognized, deleted, vanished, failed);
        }
    }
}
