package com.edem.dedup4j.management;

import com.edem.dedup4j.reconcile.ReconciliationRun;

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

    /**
     * This instance's scheduled reconciliation: whether it runs here, its cron expression and its
     * last run, which is null until the first run and is not kept across restarts.
     */
    public record Reconciliation(boolean enabled, String instanceId, String cron, ReconciliationRun lastRun) { }

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
    ) {
        /** The original shape, kept for custom {@link FailureSource}s; the attribution fields are unknown and unsequenced. */
        public Failure(UUID id, Instant occurredAt, String operation, String message) {
            this(id, occurredAt, operation, message, null, null, null, 0);
        }
    }

    /** One content row in a listing, most-shared or largest first. */
    public record Content(UUID id, String hashAlgorithm, String contentHash, long sizeBytes, String contentType,
                          long refCount) { }

    /** Best-effort offset page: counts change while paging, so a row can be skipped or repeated. */
    public record ContentPage(List<Content> items, int page, int size, long total) { }

    /**
     * One content row. {@code logicalReferences} comes from the application's
     * {@code ContentReferenceCountSource} and is null without one; it is read separately and can
     * differ from {@code refCount} while uploads or releases are in flight.
     */
    public record ContentDetail(UUID id, String hashAlgorithm, String contentHash, long sizeBytes, String contentType,
                                String originalExtension, String objectKey, String storageProvider,
                                String bucketOrContainer, long refCount, Long logicalReferences, Instant createdAt,
                                Instant updatedAt, Instant objectDeletedAt) { }

    @FunctionalInterface
    public interface FailureSource {
        List<Failure> recentFailures(Instant since);

        /**
         * Ascending by sequence number. The default empty list means the source has no sequence paging support;
         * only {@link #recentFailures} serves its failures.
         */
        default List<Failure> failuresAfter(long afterSequence, int limit) {
            return List.of();
        }
    }
}
