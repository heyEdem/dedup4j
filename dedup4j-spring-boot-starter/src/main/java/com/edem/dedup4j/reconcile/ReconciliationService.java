package com.edem.dedup4j.reconcile;

import com.edem.dedup4j.core.exception.BlobValidationException;
import com.edem.dedup4j.jpa.AssetContent;
import com.edem.dedup4j.jpa.AssetContentRepository;
import com.edem.dedup4j.jpa.ReferenceCountService;
import com.edem.dedup4j.observability.Dedup4jMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;

/**
 * Reports drift between application-owned logical references and stored
 * physical-content reference counts.
 *
 * <p>Reporting is always read-only. Repair is a separate operation and is
 * disabled unless explicitly enabled by the caller.</p>
 */
public final class ReconciliationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReconciliationService.class);

    private final AssetContentRepository repository;
    private final ReferenceCountService referenceCountService;
    private final boolean repairEnabled;
    private final Dedup4jMetrics metrics;

    public ReconciliationService(AssetContentRepository repository) {
        this(repository, null, false, new Dedup4jMetrics(null));
    }

    public ReconciliationService(
            AssetContentRepository repository,
            ReferenceCountService referenceCountService,
            boolean repairEnabled
    ) {
        this(repository, referenceCountService, repairEnabled, new Dedup4jMetrics(null));
    }

    public ReconciliationService(
            AssetContentRepository repository,
            ReferenceCountService referenceCountService,
            boolean repairEnabled,
            Dedup4jMetrics metrics
    ) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.referenceCountService = repairEnabled
                ? Objects.requireNonNull(referenceCountService, "referenceCountService must not be null")
                : referenceCountService;
        this.repairEnabled = repairEnabled;
        this.metrics = Objects.requireNonNull(metrics, "metrics must not be null");
    }

    public ReconciliationReport reconcile(LogicalReferenceCountSource source) {
        Objects.requireNonNull(source, "source must not be null");
        ReconciliationSnapshot snapshot = inspect(source);
        return new ReconciliationReport(snapshot.checkedContentCount(), snapshot.mismatches());
    }

    /**
     * Read-only pass for scheduled runs: pages through content rows by ID, so memory holds one
     * page plus the application's counts, and keeps at most {@code sampleLimit} mismatches and
     * dangling references while counting all of them. It never writes, even when called inside a
     * transaction, and stops with a {@link CancellationException} between pages once the thread is
     * interrupted.
     */
    public ReconciliationSummary summarize(LogicalReferenceCountSource source, int pageSize, int sampleLimit) {
        Objects.requireNonNull(source, "source must not be null");
        if (pageSize < 1) {
            throw new IllegalArgumentException("pageSize must be positive");
        }
        if (sampleLimit < 0) {
            throw new IllegalArgumentException("sampleLimit must not be negative");
        }
        Map<UUID, Long> expectedCounts = Objects.requireNonNull(
                source.countLogicalReferences(),
                "logical reference counts must not be null"
        );
        Set<UUID> unseen = new HashSet<>(expectedCounts.keySet());
        List<ReconciliationMismatch> mismatchSample = new ArrayList<>();
        long checked = 0;
        long mismatches = 0;
        UUID after = null;
        List<AssetContent> page;
        do {
            if (Thread.currentThread().isInterrupted()) {
                throw new CancellationException("Reconciliation interrupted after " + checked + " content rows");
            }
            page = repository.findPageAfter(after, pageSize);
            for (AssetContent content : page) {
                checked++;
                unseen.remove(content.getId());
                long expected = expectedCount(expectedCounts, content.getId());
                if (expected != content.getRefCount()) {
                    mismatches++;
                    if (mismatchSample.size() < sampleLimit) {
                        mismatchSample.add(new ReconciliationMismatch(
                                content.getId(), expected, content.getRefCount()));
                    }
                }
                after = content.getId();
            }
        } while (page.size() == pageSize);

        List<UUID> danglingSample = new ArrayList<>();
        long dangling = 0;
        for (UUID id : unseen) {
            if (expectedCount(expectedCounts, id) > 0L) {
                dangling++;
                if (danglingSample.size() < sampleLimit) {
                    danglingSample.add(id);
                }
            }
        }
        return new ReconciliationSummary(checked, mismatches, dangling, mismatchSample, danglingSample);
    }

    /**
     * Reports drift and, when explicitly enabled, adjusts each mismatched row
     * through the lock-aware reference-count service. The caller must keep a
     * transaction active for the duration of an enabled repair. Repair never
     * deletes physical objects: content it takes to zero is listed in
     * {@link ReconciliationReport#releasedContentIds()} for the caller to delete
     * after the transaction commits.
     */
    public ReconciliationReport repair(LogicalReferenceCountSource source) {
        ReconciliationSnapshot snapshot = inspect(source);
        List<UUID> released = new ArrayList<>();
        if (repairEnabled) {
            for (ReconciliationMismatch mismatch : snapshot.mismatches()) {
                adjustReferenceCount(mismatch, released);
            }
        }
        return new ReconciliationReport(snapshot.checkedContentCount(), snapshot.mismatches(), released);
    }

    private ReconciliationSnapshot inspect(LogicalReferenceCountSource source) {
        Objects.requireNonNull(source, "source must not be null");
        Map<UUID, Long> expectedCounts = Objects.requireNonNull(
                source.countLogicalReferences(),
                "logical reference counts must not be null"
        );
        List<ReconciliationMismatch> mismatches = new ArrayList<>();
        List<AssetContent> contents = repository.findAll();

        for (AssetContent content : contents) {
            long expected = expectedCount(expectedCounts, content.getId());
            long actual = content.getRefCount();
            if (expected != actual) {
                mismatches.add(new ReconciliationMismatch(content.getId(), expected, actual));
            }
        }

        return new ReconciliationSnapshot(contents.size(), mismatches);
    }

    private void adjustReferenceCount(ReconciliationMismatch mismatch, List<UUID> released) {
        long actual = mismatch.actualReferenceCount();
        long expected = mismatch.expectedReferenceCount();
        if (actual == 0L) {
            // The physical object was deleted at zero; raising the count cannot bring the bytes back.
            LOGGER.warn(
                    "event=reconcile.repair.skipped contentId={} expected={} actual=0 reason=released",
                    mismatch.assetContentId(),
                    expected
            );
            return;
        }
        if (actual < expected) {
            for (long count = actual; count < expected; count++) {
                referenceCountService.retain(mismatch.assetContentId());
            }
        } else {
            for (long count = actual; count > expected; count--) {
                if (referenceCountService.release(mismatch.assetContentId())) {
                    released.add(mismatch.assetContentId());
                }
            }
        }
        metrics.recordRepair();
    }

    private record ReconciliationSnapshot(int checkedContentCount, List<ReconciliationMismatch> mismatches) {
    }

    private static long expectedCount(Map<UUID, Long> expectedCounts, UUID assetContentId) {
        Long expected = expectedCounts.getOrDefault(assetContentId, 0L);
        if (expected == null || expected < 0L) {
            throw new BlobValidationException(
                    "Logical reference count must not be null or negative for asset content " + assetContentId
            );
        }
        return expected;
    }
}
