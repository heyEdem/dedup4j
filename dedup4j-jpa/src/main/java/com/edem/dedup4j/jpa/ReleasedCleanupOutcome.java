package com.edem.dedup4j.jpa;

/** What {@link ReferenceCountService#cleanUpReleased} found for one content row. */
public enum ReleasedCleanupOutcome {

    /** The content is referenced again, so its object was kept. */
    REFERENCED,

    /** The content is released and its object was already gone. */
    ABSENT,

    /** The content is released and its leftover object was deleted. */
    DELETED
}
