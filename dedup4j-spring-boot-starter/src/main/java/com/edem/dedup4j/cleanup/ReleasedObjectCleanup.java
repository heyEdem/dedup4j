package com.edem.dedup4j.cleanup;

import com.edem.dedup4j.core.exception.BlobStorageException;
import com.edem.dedup4j.history.UploadHistory;
import com.edem.dedup4j.jpa.AssetContent;
import com.edem.dedup4j.jpa.AssetContentRepository;
import com.edem.dedup4j.jpa.ReferenceCountService;
import com.edem.dedup4j.jpa.ReleasedCleanupOutcome;
import com.edem.dedup4j.observability.Dedup4jMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CancellationException;

/**
 * Removes objects left in storage by released content, such as after a failed post-release delete
 * or a crash between the release and its delete. Nothing runs it automatically: call {@link #run()}
 * from the application, on one instance per shared database.
 *
 * <p>Each released row is handled in its own transaction through
 * {@link ReferenceCountService#cleanUpReleased}, which locks the row and keeps the object of
 * content an upload referenced again. A failed row is logged, counted and recorded in failure
 * history, and the pass carries on; the next pass retries it. An interrupted pass stops before the
 * next row with a {@link CancellationException}.</p>
 */
public final class ReleasedObjectCleanup {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReleasedObjectCleanup.class);
    private static final String DELETE = "delete";

    private final AssetContentRepository repository;
    private final ReferenceCountService referenceCountService;
    private final TransactionTemplate transactions;
    private final TransactionTemplate readOnly;
    private final UploadHistory history;
    private final Dedup4jMetrics metrics;
    private final int pageSize;

    public ReleasedObjectCleanup(
            AssetContentRepository repository,
            ReferenceCountService referenceCountService,
            PlatformTransactionManager transactionManager,
            UploadHistory history,
            Dedup4jMetrics metrics,
            int pageSize
    ) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.referenceCountService = Objects.requireNonNull(referenceCountService, "referenceCountService must not be null");
        Objects.requireNonNull(transactionManager, "transactionManager must not be null");
        this.history = Objects.requireNonNull(history, "history must not be null");
        this.metrics = Objects.requireNonNull(metrics, "metrics must not be null");
        if (pageSize < 1) {
            throw new IllegalArgumentException("pageSize must be positive");
        }
        this.pageSize = pageSize;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.readOnly.setReadOnly(true);
    }

    public ReleasedCleanupResult run() {
        long checked = 0;
        long deleted = 0;
        long absent = 0;
        long referenced = 0;
        long failed = 0;
        UUID after = null;
        List<AssetContent> page;
        do {
            UUID pageAfter = after;
            page = readOnly.execute(status -> repository.findReleasedPageAfter(pageAfter, pageSize));
            for (AssetContent content : page) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new CancellationException("Released object cleanup interrupted after " + checked + " rows");
                }
                after = content.getId();
                checked++;
                try {
                    switch (cleanUp(content.getId())) {
                        case DELETED -> {
                            deleted++;
                            metrics.recordReleasedObjectDeleted();
                        }
                        case ABSENT -> absent++;
                        case REFERENCED -> referenced++;
                    }
                } catch (RuntimeException failure) {
                    failed++;
                    recordFailure(content, failure);
                }
            }
        } while (page.size() == pageSize);
        ReleasedCleanupResult result = new ReleasedCleanupResult(checked, deleted, absent, referenced, failed);
        metrics.recordReleasedCleanupCompleted(failed);
        log(result);
        return result;
    }

    private ReleasedCleanupOutcome cleanUp(UUID assetContentId) {
        return Objects.requireNonNull(
                transactions.execute(status -> referenceCountService.cleanUpReleased(assetContentId))
        );
    }

    /** Runs after the row's transaction has ended, so it never waits on that transaction's lock. */
    private void recordFailure(AssetContent content, RuntimeException failure) {
        if (failure instanceof BlobStorageException) {
            metrics.recordDeleteFailure();
        }
        LOGGER.warn(
                "event=blob.cleanup.failed contentId={} objectKey={} errorType={} failureMessage={} "
                        + "detail=\"released object left in storage; retried next pass\"",
                content.getId(),
                content.getObjectKey(),
                failure.getClass().getSimpleName(),
                failure.getMessage(),
                failure
        );
        try {
            history.recordFailure(DELETE, content.getContentHash(), content.getObjectKey(), failure);
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

    private static void log(ReleasedCleanupResult result) {
        String message = "event=blob.cleanup.run.completed checked={} deleted={} alreadyAbsent={} referenced={} failed={}";
        Object[] counts = {result.checked(), result.deleted(), result.alreadyAbsent(), result.referenced(), result.failed()};
        if (result.failed() == 0) {
            LOGGER.info(message, counts);
        } else {
            LOGGER.warn(message, counts);
        }
    }
}
