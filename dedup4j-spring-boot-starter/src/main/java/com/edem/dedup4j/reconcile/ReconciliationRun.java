package com.edem.dedup4j.reconcile;

import java.time.Instant;
import java.util.Objects;

/**
 * Outcome of one scheduled or on-demand reconciliation pass. A succeeded report run carries its
 * summary and a succeeded repair run its repair result; a failed run carries the failure type and
 * message instead. A repair run succeeds when the pass completes, even if some rows failed to repair
 * ({@link CoordinatedRepairResult#failed()}).
 */
public record ReconciliationRun(
        Instant startedAt,
        Instant finishedAt,
        ReconciliationMode mode,
        Outcome outcome,
        ReconciliationSummary summary,
        CoordinatedRepairResult repair,
        String failureType,
        String failureMessage
) {
    public enum Outcome {
        SUCCEEDED,
        FAILED
    }

    public ReconciliationRun {
        Objects.requireNonNull(startedAt, "startedAt must not be null");
        Objects.requireNonNull(finishedAt, "finishedAt must not be null");
        Objects.requireNonNull(mode, "mode must not be null");
        Objects.requireNonNull(outcome, "outcome must not be null");
    }

    static ReconciliationRun succeeded(Instant startedAt, Instant finishedAt, ReconciliationSummary summary) {
        return new ReconciliationRun(startedAt, finishedAt, ReconciliationMode.REPORT, Outcome.SUCCEEDED,
                Objects.requireNonNull(summary, "summary must not be null"), null, null, null);
    }

    static ReconciliationRun repaired(Instant startedAt, Instant finishedAt, CoordinatedRepairResult repair) {
        return new ReconciliationRun(startedAt, finishedAt, ReconciliationMode.REPAIR, Outcome.SUCCEEDED, null,
                Objects.requireNonNull(repair, "repair must not be null"), null, null);
    }

    static ReconciliationRun failed(
            Instant startedAt, Instant finishedAt, ReconciliationMode mode, RuntimeException failure) {
        return new ReconciliationRun(startedAt, finishedAt, mode, Outcome.FAILED, null, null,
                failure.getClass().getSimpleName(), failure.getMessage());
    }
}
