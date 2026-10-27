package com.edem.dedup4j.reconcile;

import java.time.Instant;
import java.util.Objects;

/**
 * Outcome of one scheduled or on-demand reconciliation pass. A succeeded run carries its
 * summary; a failed run carries the failure type and message instead.
 */
public record ReconciliationRun(
        Instant startedAt,
        Instant finishedAt,
        Outcome outcome,
        ReconciliationSummary summary,
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
        Objects.requireNonNull(outcome, "outcome must not be null");
    }

    static ReconciliationRun succeeded(Instant startedAt, Instant finishedAt, ReconciliationSummary summary) {
        return new ReconciliationRun(startedAt, finishedAt, Outcome.SUCCEEDED,
                Objects.requireNonNull(summary, "summary must not be null"), null, null);
    }

    static ReconciliationRun failed(Instant startedAt, Instant finishedAt, RuntimeException failure) {
        return new ReconciliationRun(startedAt, finishedAt, Outcome.FAILED, null,
                failure.getClass().getSimpleName(), failure.getMessage());
    }
}
