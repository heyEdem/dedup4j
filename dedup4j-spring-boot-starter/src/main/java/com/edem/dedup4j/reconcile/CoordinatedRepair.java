package com.edem.dedup4j.reconcile;

import com.edem.dedup4j.core.exception.BlobStorageException;
import com.edem.dedup4j.core.exception.BlobValidationException;
import com.edem.dedup4j.history.UploadHistory;
import com.edem.dedup4j.jpa.AssetContent;
import com.edem.dedup4j.jpa.AssetContentRepository;
import com.edem.dedup4j.jpa.ReferenceCountService;
import com.edem.dedup4j.jpa.ReferenceRepairResult;
import com.edem.dedup4j.jpa.ReferenceRepairService;
import com.edem.dedup4j.observability.Dedup4jMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.InvalidIsolationLevelException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CancellationException;

/**
 * Repairs reference-count drift without acting on application writes still in flight, for
 * applications that follow the writer contract on {@link ContentReferenceCountSource}.
 *
 * <p>A read-only discovery pass compares every content row with the application's bulk counts and
 * keeps the rows that differ. Discovery only nominates: each candidate is then rechecked in its own
 * READ COMMITTED transaction that locks the row, asks the application for that content's count, and
 * changes the count only if they still differ, recording the repair in the append-only audit in the
 * same transaction. A writer holding the lock makes repair wait; once it commits or rolls back, the
 * recheck sees its result. Content repaired to zero has its object deleted after commit through
 * {@link ReferenceCountService#deleteIfReleased}, which rechecks the count under the lock, so an
 * upload that restores it first keeps its bytes.</p>
 *
 * <p>Content at zero that the application still references is reported and left at zero, and so
 * are references to unknown content. Rows created or drifting after discovery passed them wait for
 * the next pass. Nothing schedules a pass; call {@link #run} outside any transaction.</p>
 */
public final class CoordinatedRepair {

    private static final Logger LOGGER = LoggerFactory.getLogger(CoordinatedRepair.class);
    private static final String DELETE = "delete";

    private final AssetContentRepository contents;
    private final ReferenceRepairService repairService;
    private final ReferenceCountService referenceCountService;
    private final TransactionTemplate repairs;
    private final TransactionTemplate readOnly;
    private final UploadHistory history;
    private final Dedup4jMetrics metrics;
    private final String instanceId;
    private final Clock clock;
    private final int pageSize;

    public CoordinatedRepair(
            AssetContentRepository contents,
            ReferenceRepairService repairService,
            ReferenceCountService referenceCountService,
            PlatformTransactionManager transactionManager,
            UploadHistory history,
            Dedup4jMetrics metrics,
            String instanceId,
            Clock clock,
            int pageSize
    ) {
        this.contents = Objects.requireNonNull(contents, "contents must not be null");
        this.repairService = Objects.requireNonNull(repairService, "repairService must not be null");
        this.referenceCountService = Objects.requireNonNull(referenceCountService, "referenceCountService must not be null");
        Objects.requireNonNull(transactionManager, "transactionManager must not be null");
        this.history = Objects.requireNonNull(history, "history must not be null");
        this.metrics = Objects.requireNonNull(metrics, "metrics must not be null");
        if (instanceId == null || instanceId.isBlank()) {
            throw new IllegalArgumentException("instanceId must not be blank");
        }
        this.instanceId = instanceId;
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        if (pageSize < 1) {
            throw new IllegalArgumentException("pageSize must be positive");
        }
        this.pageSize = pageSize;
        // A rejected isolation level fails the transaction's begin, so no repair runs without it.
        this.repairs = new TransactionTemplate(transactionManager);
        this.repairs.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.repairs.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.readOnly.setReadOnly(true);
    }

    /**
     * @param candidates nominates rows to recheck; it may be stale or approximate
     * @param authority  the application's count for one content, read under that content's lock
     * @throws IllegalStateException when called inside a transaction, whose locks repair could wait on forever,
     *                               or when the transaction manager cannot run READ COMMITTED transactions
     */
    public CoordinatedRepairResult run(LogicalReferenceCountSource candidates, ContentReferenceCountSource authority) {
        Objects.requireNonNull(candidates, "candidates must not be null");
        Objects.requireNonNull(authority, "authority must not be null");
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("CoordinatedRepair.run must be called outside a transaction");
        }
        UUID runId = UUID.randomUUID();
        Discovery discovery = discover(candidates);
        long repaired = 0;
        long consistent = 0;
        long released = 0;
        long failed = 0;
        for (AssetContentRef candidate : discovery.candidates()) {
            if (Thread.currentThread().isInterrupted()) {
                throw new CancellationException("Coordinated repair interrupted after " + repaired + " repairs");
            }
            ReferenceRepairResult result;
            try {
                result = Objects.requireNonNull(repairs.execute(status -> repairService.repair(
                        candidate.id(), authority::countLogicalReferences, runId, instanceId, clock.instant())));
            } catch (InvalidIsolationLevelException unsupported) {
                throw new IllegalStateException("CoordinatedRepair needs a transaction manager that can run "
                        + "READ COMMITTED transactions; no count was changed", unsupported);
            } catch (RuntimeException failure) {
                failed++;
                LOGGER.warn(
                        "event=reconcile.repair.failed runId={} contentId={} errorType={} failureMessage={}",
                        runId, candidate.id(), failure.getClass().getSimpleName(), failure.getMessage(), failure);
                continue;
            }
            switch (result.outcome()) {
                case CONSISTENT -> consistent++;
                case RELEASED -> {
                    released++;
                    LOGGER.warn(
                            "event=reconcile.repair.skipped runId={} contentId={} expected={} actual=0 reason=released",
                            runId, candidate.id(), result.logicalCount());
                }
                case REPAIRED -> {
                    repaired++;
                    metrics.recordRepair();
                    LOGGER.info("event=reconcile.repair.applied runId={} contentId={} previous={} repaired={}",
                            runId, candidate.id(), result.storedCount(), result.logicalCount());
                    if (result.released()) {
                        deleteReleased(candidate);
                    }
                }
            }
        }
        CoordinatedRepairResult result = new CoordinatedRepairResult(
                runId, discovery.checked(), discovery.candidates().size(), repaired, consistent, released, failed);
        String message = "event=reconcile.repair.completed runId={} checked={} candidates={} repaired={} "
                + "consistent={} released={} failed={}";
        Object[] counts = {runId, result.checked(), result.candidates(), result.repaired(), result.consistent(),
                result.released(), result.failed()};
        if (failed == 0) {
            LOGGER.info(message, counts);
        } else {
            LOGGER.warn(message, counts);
        }
        return result;
    }

    private Discovery discover(LogicalReferenceCountSource source) {
        Map<UUID, Long> expectedCounts = Objects.requireNonNull(
                source.countLogicalReferences(), "logical reference counts must not be null");
        List<AssetContentRef> candidates = new ArrayList<>();
        long checked = 0;
        UUID after = null;
        List<AssetContent> page;
        do {
            if (Thread.currentThread().isInterrupted()) {
                throw new CancellationException("Coordinated repair interrupted after " + checked + " content rows");
            }
            UUID pageAfter = after;
            page = Objects.requireNonNull(readOnly.execute(status -> contents.findPageAfter(pageAfter, pageSize)));
            for (AssetContent content : page) {
                checked++;
                Long expected = expectedCounts.getOrDefault(content.getId(), 0L);
                if (expected == null || expected < 0L) {
                    throw new BlobValidationException("Logical reference count must not be null or negative "
                            + "for asset content " + content.getId());
                }
                if (expected != content.getRefCount()) {
                    candidates.add(new AssetContentRef(content.getId(), content.getContentHash(), content.getObjectKey()));
                }
                after = content.getId();
            }
        } while (page.size() == pageSize);
        return new Discovery(checked, candidates);
    }

    /** Never thrown: the repair has committed, and a failed delete leaves the object for {@code ReleasedObjectCleanup}. */
    private void deleteReleased(AssetContentRef content) {
        try {
            repairs.executeWithoutResult(status -> referenceCountService.deleteIfReleased(content.id()));
        } catch (RuntimeException failure) {
            if (failure instanceof BlobStorageException) {
                metrics.recordDeleteFailure();
            }
            LOGGER.warn(
                    "event=blob.cleanup.failed contentId={} objectKey={} errorType={} failureMessage={} "
                            + "detail=\"repaired to zero; object left in storage\"",
                    content.id(), content.objectKey(), failure.getClass().getSimpleName(), failure.getMessage(), failure);
            try {
                history.recordFailure(DELETE, content.contentHash(), content.objectKey(), failure);
            } catch (RuntimeException recordingFailure) {
                LOGGER.warn("event=history.failure.unrecorded operation={} errorType={} failureMessage={}",
                        DELETE, recordingFailure.getClass().getSimpleName(), recordingFailure.getMessage(),
                        recordingFailure);
            }
        }
    }

    private record AssetContentRef(UUID id, String contentHash, String objectKey) {
    }

    private record Discovery(long checked, List<AssetContentRef> candidates) {
    }
}
