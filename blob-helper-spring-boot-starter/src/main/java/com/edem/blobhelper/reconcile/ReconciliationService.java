package com.edem.blobhelper.reconcile;

import com.edem.dedup4j.core.exception.BlobValidationException;
import com.edem.blobhelper.jpa.AssetContent;
import com.edem.blobhelper.jpa.AssetContentRepository;
import com.edem.blobhelper.jpa.ReferenceCountService;
import com.edem.blobhelper.observability.BlobHelperMetrics;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Reports drift between application-owned logical references and stored
 * physical-content reference counts.
 *
 * <p>Reporting is always read-only. Repair is a separate operation and is
 * disabled unless explicitly enabled by the caller.</p>
 */
public final class ReconciliationService {

    private final AssetContentRepository repository;
    private final ReferenceCountService referenceCountService;
    private final boolean repairEnabled;
    private final BlobHelperMetrics metrics;

    public ReconciliationService(AssetContentRepository repository) {
        this(repository, null, false, new BlobHelperMetrics(null));
    }

    public ReconciliationService(
            AssetContentRepository repository,
            ReferenceCountService referenceCountService,
            boolean repairEnabled
    ) {
        this(repository, referenceCountService, repairEnabled, new BlobHelperMetrics(null));
    }

    public ReconciliationService(
            AssetContentRepository repository,
            ReferenceCountService referenceCountService,
            boolean repairEnabled,
            BlobHelperMetrics metrics
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
     * Reports drift and, when explicitly enabled, adjusts each mismatched row
     * through the lock-aware reference-count service. The caller must keep a
     * transaction active for the duration of an enabled repair.
     */
    public ReconciliationReport repair(LogicalReferenceCountSource source) {
        ReconciliationSnapshot snapshot = inspect(source);
        if (repairEnabled) {
            for (ReconciliationMismatch mismatch : snapshot.mismatches()) {
                adjustReferenceCount(mismatch);
            }
        }
        return new ReconciliationReport(snapshot.checkedContentCount(), snapshot.mismatches());
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

    private void adjustReferenceCount(ReconciliationMismatch mismatch) {
        long actual = mismatch.actualReferenceCount();
        long expected = mismatch.expectedReferenceCount();
        if (actual < expected) {
            for (long count = actual; count < expected; count++) {
                referenceCountService.retain(mismatch.assetContentId());
            }
        } else {
            for (long count = actual; count > expected; count--) {
                referenceCountService.release(mismatch.assetContentId());
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
