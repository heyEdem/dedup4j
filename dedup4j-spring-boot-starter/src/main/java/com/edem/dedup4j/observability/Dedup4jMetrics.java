package com.edem.dedup4j.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Optional Micrometer instrumentation for dedup4j operations.
 *
 * <p>A {@code null} registry disables instrumentation while preserving the
 * operation behavior. Applications using Spring Boot Actuator can provide
 * their normal {@link MeterRegistry} and receive the meters automatically.</p>
 */
public final class Dedup4jMetrics {

    private static final String UPLOADS = "dedup4j.uploads";
    private static final String DUPLICATES = "dedup4j.duplicates";
    private static final String SKIPPED_PHYSICAL_WRITES = "dedup4j.skipped.physical.writes";
    private static final String ACCEPTED_BYTES = "dedup4j.bytes.accepted";
    private static final String AVOIDED_BYTES = "dedup4j.bytes.avoided";
    private static final String HASHING = "dedup4j.hashing";
    private static final String STORAGE_WRITES = "dedup4j.storage.writes";
    private static final String DELETE_FAILURES = "dedup4j.storage.delete.failures";
    private static final String REPAIRS = "dedup4j.repairs";

    private final MeterRegistry registry;

    public Dedup4jMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordUpload(long bytes, boolean duplicate) {
        if (registry == null) {
            return;
        }
        registry.counter(UPLOADS).increment();
        registry.counter(ACCEPTED_BYTES).increment(bytes);
        if (duplicate) {
            registry.counter(DUPLICATES).increment();
            registry.counter(SKIPPED_PHYSICAL_WRITES).increment();
            registry.counter(AVOIDED_BYTES).increment(bytes);
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

    private <T> T record(String timerName, Supplier<T> operation) {
        if (registry == null) {
            return operation.get();
        }
        return registry.timer(timerName).record(operation);
    }
}
