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

    public record Info(String instanceId, String instanceName, String provider, String datasetId) { }

    public record Health(String status, Instant observedAt) { }

    public record Metrics(
            String datasetId,
            String totalsScope,
            long uploads,
            long duplicates,
            long physicalWrites,
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
            String message,
            String instanceId,
            String contentHash,
            String objectKey,
            long sequenceNo
    ) { }

    @FunctionalInterface
    public interface FailureSource {
        List<Failure> recentFailures(Instant since);

        /** Ascending by sequence number; the default offers no paging support. */
        default List<Failure> failuresAfter(long afterSequence, int limit) {
            return List.of();
        }
    }
}
