package com.edem.dedup4j.reconcile;

import com.edem.dedup4j.core.exception.BlobValidationException;

import java.util.List;
import java.util.UUID;

/**
 * Bounded result of a read-only reconciliation pass. The counts cover every row checked; the
 * samples hold at most the requested number of entries, so the summary stays small however much
 * drift there is.
 *
 * <p>A dangling reference is a content ID the application counts at least one reference to but
 * dedup4j has no content row for.</p>
 */
public record ReconciliationSummary(
        long checkedContentCount,
        long mismatchCount,
        long danglingReferenceCount,
        List<ReconciliationMismatch> mismatchSample,
        List<UUID> danglingReferenceSample
) {
    public ReconciliationSummary {
        if (checkedContentCount < 0 || mismatchCount < 0 || danglingReferenceCount < 0) {
            throw new BlobValidationException("reconciliation counts must not be negative");
        }
        if (mismatchSample == null || danglingReferenceSample == null) {
            throw new BlobValidationException("reconciliation samples must not be null");
        }
        mismatchSample = List.copyOf(mismatchSample);
        danglingReferenceSample = List.copyOf(danglingReferenceSample);
    }
}
