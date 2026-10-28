package com.edem.dedup4j.cleanup;

/**
 * Counts from one {@link ReleasedObjectCleanup} pass over content at zero references.
 *
 * @param checked        released rows examined
 * @param deleted        leftover objects found and deleted
 * @param alreadyAbsent  released rows whose object was already gone
 * @param referenced     rows referenced again before the pass locked them; their objects are kept
 * @param failed         rows whose lock, existence check, delete or commit failed; their objects
 *                       may still be in storage
 */
public record ReleasedCleanupResult(long checked, long deleted, long alreadyAbsent, long referenced, long failed) {

    public ReleasedCleanupResult {
        if (checked < 0 || deleted < 0 || alreadyAbsent < 0 || referenced < 0 || failed < 0) {
            throw new IllegalArgumentException("counts must not be negative");
        }
    }

    /** Objects this pass deleted plus rows it could not resolve, an upper bound on leftovers found. */
    public long leftoverCount() {
        return deleted + failed;
    }
}
