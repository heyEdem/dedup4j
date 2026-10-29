package com.edem.dedup4j.reconcile;

import java.util.UUID;

/**
 * Counts from one {@link CoordinatedRepair} pass.
 *
 * @param runId       identifies this pass's rows in the repair audit
 * @param checked     content rows the discovery pass compared
 * @param candidates  rows whose counts differed in discovery and were rechecked under their lock
 * @param repaired    rows whose count was changed and recorded
 * @param consistent  candidates that matched once locked, such as writes still in flight during discovery
 * @param released    candidates at zero the application still references; left at zero
 * @param failed      candidates whose lock, lookup, audit record or commit failed; nothing changed
 */
public record CoordinatedRepairResult(
        UUID runId,
        long checked,
        long candidates,
        long repaired,
        long consistent,
        long released,
        long failed
) {

    public CoordinatedRepairResult {
        if (runId == null) {
            throw new IllegalArgumentException("runId must not be null");
        }
        if (checked < 0 || candidates < 0 || repaired < 0 || consistent < 0 || released < 0 || failed < 0) {
            throw new IllegalArgumentException("counts must not be negative");
        }
    }
}
