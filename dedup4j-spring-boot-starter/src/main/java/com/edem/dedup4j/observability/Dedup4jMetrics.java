package com.edem.dedup4j.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Optional Micrometer instrumentation for dedup4j operations.
 *
 * <p>A {@code null} registry disables instrumentation while preserving the
 * operation behavior. Applications using Spring Boot Actuator can provide
 * their normal {@link MeterRegistry} and receive the meters automatically.</p>
 */
public final class Dedup4jMetrics {

    public static final String UPLOADS = "dedup4j.uploads";
    public static final String DUPLICATES = "dedup4j.duplicates";
    public static final String SKIPPED_PHYSICAL_WRITES = "dedup4j.skipped.physical.writes";
    public static final String ACCEPTED_BYTES = "dedup4j.bytes.accepted";
    public static final String AVOIDED_BYTES = "dedup4j.bytes.avoided";
    private static final String HASHING = "dedup4j.hashing";
    private static final String STORAGE_WRITES = "dedup4j.storage.writes";
    private static final String DELETE_FAILURES = "dedup4j.storage.delete.failures";
    private static final String REPAIRS = "dedup4j.repairs";
    private static final String RECONCILIATION_RUNS = "dedup4j.reconciliation.runs";
    private static final String RECONCILIATION_MISMATCHES = "dedup4j.reconciliation.mismatches";
    private static final String RECONCILIATION_DANGLING = "dedup4j.reconciliation.dangling";
    private static final String CLEANUP_DELETED = "dedup4j.cleanup.deleted";
    private static final String ORPHANS_DELETED = "dedup4j.cleanup.orphans.deleted";
    private static final String CLEANUP_REMAINING = "dedup4j.cleanup.remaining";

    private final MeterRegistry registry;
    private final AtomicLong lastMismatches = new AtomicLong();
    private final AtomicLong lastDangling = new AtomicLong();
    private final AtomicLong cleanupRemaining = new AtomicLong();

    public Dedup4jMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** Call only after the upload's transaction has committed. */
    public void recordUpload(long bytes, boolean duplicate, boolean physicalWrite) {
        if (registry == null) {
            return;
        }
        registry.counter(UPLOADS).increment();
        registry.counter(ACCEPTED_BYTES).increment(bytes);
        if (duplicate) {
            registry.counter(DUPLICATES).increment();
            registry.counter(AVOIDED_BYTES).increment(bytes);
        }
        if (!physicalWrite) {
            registry.counter(SKIPPED_PHYSICAL_WRITES).increment();
        }
    }

    public <T> T recordHashing(Supplier<T> operation) {
        Objects.requireNonNull(operation, "operation must not be null");
        return record(HASHING, operation);
    }

    public <T> T recordStorageWrite(Supplier<T> operation) {
        Objects.requireNonNull(operation, "operation must not be null");
        return record(STORAGE_WRITES, operation);
    }

    public void recordDeleteFailure() {
        if (registry != null) {
            registry.counter(DELETE_FAILURES).increment();
        }
    }

    public void recordRepair() {
        if (registry != null) {
            registry.counter(REPAIRS).increment();
        }
    }

    public void recordReconciliationRun(boolean succeeded, Duration duration) {
        if (registry != null) {
            registry.timer(RECONCILIATION_RUNS, "outcome", succeeded ? "success" : "failure").record(duration);
        }
    }

    /** Gauges hold the counts from the last successful report run; repair runs leave them unchanged. */
    public void recordReconciliationDrift(long mismatches, long dangling) {
        lastMismatches.set(mismatches);
        lastDangling.set(dangling);
        if (registry != null) {
            registry.gauge(RECONCILIATION_MISMATCHES, lastMismatches);
            registry.gauge(RECONCILIATION_DANGLING, lastDangling);
        }
    }

    /** Counted per row as soon as its delete commits, so an aborted pass keeps what it deleted. */
    public void recordReleasedObjectDeleted() {
        if (registry != null) {
            registry.counter(CLEANUP_DELETED).increment();
        }
    }

    /** The gauge holds the failed rows of the last completed pass; their objects may remain. */
    /** One object with no content row, deleted by the orphaned object sweep. */
    public void recordOrphanedObjectDeleted() {
        if (registry != null) {
            registry.counter(ORPHANS_DELETED).increment();
        }
    }

    public void recordReleasedCleanupCompleted(long failed) {
        cleanupRemaining.set(failed);
        if (registry != null) {
            registry.gauge(CLEANUP_REMAINING, cleanupRemaining);
        }
    }

    private <T> T record(String timerName, Supplier<T> operation) {
        if (registry == null) {
            return operation.get();
        }
        return registry.timer(timerName).record(operation);
    }
}
