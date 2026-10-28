package com.edem.dedup4j.reconcile;

import com.edem.dedup4j.core.exception.BlobValidationException;

import java.util.List;
import java.util.UUID;

/**
 * Immutable result of comparing application-owned and stored reference
 * counts. Creating a report never implies that a repair should be performed.
 *
 * <p>{@code releasedContentIds} lists content a repair took to zero references. Their objects are
 * still in storage: once the repair transaction commits, the caller deletes each through
 * {@code ReferenceCountService.deleteIfReleased} in a new transaction.</p>
 */
public record ReconciliationReport(
        long checkedContentCount,
        List<ReconciliationMismatch> mismatches,
        List<UUID> releasedContentIds
) {

    public ReconciliationReport(long checkedContentCount, List<ReconciliationMismatch> mismatches) {
        this(checkedContentCount, mismatches, List.of());
    }

    public ReconciliationReport {
        if (checkedContentCount < 0) {
            throw new BlobValidationException("checkedContentCount must not be negative");
        }
        if (mismatches == null) {
            throw new BlobValidationException("mismatches must not be null");
        }
        mismatches = List.copyOf(mismatches);
        if (releasedContentIds == null) {
            throw new BlobValidationException("releasedContentIds must not be null");
        }
        releasedContentIds = List.copyOf(releasedContentIds);
    }
}
