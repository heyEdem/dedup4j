package com.edem.dedup4j.jpa;

/** One row's repair: the outcome, the count found under the lock and the application's count. */
public record ReferenceRepairResult(ReferenceRepairOutcome outcome, long storedCount, long logicalCount) {

    /** Whether this repair took the content to zero, so its object is due for deletion. */
    public boolean released() {
        return outcome == ReferenceRepairOutcome.REPAIRED && logicalCount == 0L;
    }
}
