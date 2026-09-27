package com.edem.dedup4j.management;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record Dedup4jManagementSnapshot(
        Info info,
        Health health,
        Metrics metrics,
        List<Failure> failures
) {
    public Dedup4jManagementSnapshot {
        failures = List.copyOf(failures);
    }

    public record Info(String instanceId, String instanceName, String provider) { }

    public record Health(String status, Instant observedAt) { }

    public record Metrics(
            long uploads,
            long duplicates,
            long skippedPhysicalWrites,
            long acceptedBytes,
            long avoidedBytes,
            long contentCount,
            long physicalBytes
    ) { }

    public record Failure(
            UUID id,
            Instant occurredAt,
            String operation,
            String message
    ) { }

    @FunctionalInterface
    public interface FailureSource {
        List<Failure> recentFailures(Instant since);
    }
}
