package com.edem.dedup4j.cleanup;

/**
 * Counts from one {@link OrphanedObjectSweep} pass over the objects under the key prefix.
 *
 * @param listed       objects listed under the prefix
 * @param recent       objects written within the grace period, left for a later pass
 * @param owned        objects a content row points to, including rows committed during the pass
 *                     and content an upload restored before the delete
 * @param unrecognized objects whose bytes do not hash to their own key; never deleted
 * @param deleted      orphaned objects this pass deleted
 * @param vanished     orphaned objects already gone when the pass read or deleted them
 * @param failed       objects whose read, adoption or delete failed; retried next pass
 */
public record OrphanSweepResult(
        long listed, long recent, long owned, long unrecognized, long deleted, long vanished, long failed
) {

    public OrphanSweepResult {
        if (listed < 0 || recent < 0 || owned < 0 || unrecognized < 0 || deleted < 0 || vanished < 0 || failed < 0) {
            throw new IllegalArgumentException("counts must not be negative");
        }
    }
}
