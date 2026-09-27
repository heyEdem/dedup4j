package com.edem.dedup4j.dashboard.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class DashboardView {
    private DashboardView() { }
    public record Overview(long instanceCount, long healthyInstanceCount, long uploads, long duplicates,
                           long physicalUploads, long logicalBytes, long physicalBytes, long avoidedBytes,
                           double duplicateRate, long newUploads, long contentCount, List<TrendPoint> trend) { }
    public record TrendPoint(Instant observedAt, long logicalBytes, long physicalBytes, long avoidedBytes,
                             long uploads, long duplicates) { }
    public record Instance(UUID instanceId, String instanceName, String advertisedUrl, String status,
                            Instant registeredAt, Instant lastSeenAt, Instant lastFailureAt, String lastFailureMessage,
                            long uploads, long newUploads, long duplicates, double duplicateRate,
                            long contentCount, long physicalBytes, long avoidedBytes) { }
    public record History(UUID instanceId, List<MetricPoint> points) { }
    public record MetricPoint(Instant observedAt, long logicalBytes, long physicalBytes, long avoidedBytes,
                              long uploads, long duplicates, long skippedPhysicalWrites, long contentCount) { }
    public record Failure(UUID id, UUID instanceId, Instant occurredAt, String operation, String message) { }
}
