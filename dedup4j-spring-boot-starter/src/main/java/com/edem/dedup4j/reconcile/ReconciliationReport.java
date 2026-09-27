package com.edem.dedup4j.reconcile;

import com.edem.dedup4j.core.exception.BlobValidationException;

import java.util.List;

/**
 * Immutable result of comparing application-owned and stored reference
 * counts. Creating a report never implies that a repair should be performed.
 */
public record ReconciliationReport(
        long checkedContentCount,
        List<ReconciliationMismatch> mismatches
) {

    public ReconciliationReport {
        if (checkedContentCount < 0) {
            throw new BlobValidationException("checkedContentCount must not be negative");
        }
        if (mismatches == null) {
            throw new BlobValidationException("mismatches must not be null");
        }
        mismatches = List.copyOf(mismatches);
    }
}
