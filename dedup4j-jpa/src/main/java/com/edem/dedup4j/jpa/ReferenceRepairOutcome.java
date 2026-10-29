package com.edem.dedup4j.jpa;

/** What {@link ReferenceRepairService#repair} did with one content row under its lock. */
public enum ReferenceRepairOutcome {
    /** The stored count already matched the application's count, so nothing changed. */
    CONSISTENT,
    /** The count was zero but the application still references the content; left at zero. */
    RELEASED,
    /** The count was set to the application's count and the repair recorded. */
    REPAIRED
}
