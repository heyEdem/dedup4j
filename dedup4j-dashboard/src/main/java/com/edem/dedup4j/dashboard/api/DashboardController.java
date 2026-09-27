package com.edem.dedup4j.dashboard.api;

import com.edem.dedup4j.dashboard.persistence.FailureEventRepository;
import com.edem.dedup4j.dashboard.persistence.InstanceRepository;
import com.edem.dedup4j.dashboard.persistence.MetricSnapshotRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class DashboardController {
    private final InstanceRepository instances;
    private final MetricSnapshotRepository snapshots;
    private final FailureEventRepository failures;

    public DashboardController(InstanceRepository instances, MetricSnapshotRepository snapshots, FailureEventRepository failures) {
        this.instances = instances; this.snapshots = snapshots; this.failures = failures;
    }

    @GetMapping("/overview")
    public DashboardView.Overview overview() {
        var all = snapshots.findAll();
        var registered = instances.findAll();
        long uploads = all.stream().mapToLong(MetricSnapshotRepository.Snapshot::uploads).sum();
        long duplicates = all.stream().mapToLong(MetricSnapshotRepository.Snapshot::duplicates).sum();
        long logical = all.stream().mapToLong(MetricSnapshotRepository.Snapshot::logicalBytes).sum();
        long physical = all.stream().mapToLong(MetricSnapshotRepository.Snapshot::physicalBytes).sum();
        long avoided = all.stream().mapToLong(MetricSnapshotRepository.Snapshot::avoidedBytes).sum();
        var trend = all.stream().map(s -> new DashboardView.TrendPoint(s.observedAt(), s.logicalBytes(), s.physicalBytes(), s.avoidedBytes(), s.uploads(), s.duplicates())).toList();
        long contentCount = registered.stream().mapToLong(i -> metricsFor(i.id()).contentCount()).sum();
        return new DashboardView.Overview(registered.size(), registered.stream().filter(i -> "HEALTHY".equals(i.status())).count(), uploads, duplicates, all.stream().mapToLong(MetricSnapshotRepository.Snapshot::skippedPhysicalWrites).sum(), logical, physical, avoided, uploads == 0 ? 0 : (double) duplicates / uploads, uploads - duplicates, contentCount, trend);
    }

    @GetMapping("/instances/status")
    public List<DashboardView.Instance> instanceStatus() { return instances.findAll().stream().map(this::view).toList(); }

    @GetMapping("/instances/{id}/history")
    public DashboardView.History history(@PathVariable("id") UUID id) {
        return new DashboardView.History(id, snapshots.findByInstance(id).stream().map(s -> new DashboardView.MetricPoint(s.observedAt(), s.logicalBytes(), s.physicalBytes(), s.avoidedBytes(), s.uploads(), s.duplicates(), s.skippedPhysicalWrites(), s.contentCount())).toList());
    }

    @GetMapping("/failures")
    public List<DashboardView.Failure> failures(@RequestParam(name = "since", required = false) Instant since) {
        Instant boundary = since == null ? Instant.now().minus(7, ChronoUnit.DAYS) : since;
        return failures.findSince(boundary).stream().map(f -> new DashboardView.Failure(f.id(), f.instanceId(), f.occurredAt(), f.operation(), f.message())).toList();
    }

    private DashboardView.Instance view(InstanceRepository.Instance i) {
        var metrics = metricsFor(i.id());
        long uploads = metrics.uploads();
        long duplicates = metrics.duplicates();
        return new DashboardView.Instance(i.id(), i.name(), i.url(), i.status(), i.registeredAt(), i.lastSeenAt(), i.lastFailureAt(), i.lastFailureMessage(), uploads, uploads - duplicates, duplicates, uploads == 0 ? 0 : (double) duplicates / uploads, metrics.contentCount(), metrics.physicalBytes(), metrics.avoidedBytes());
    }

    private InstanceMetrics metricsFor(UUID instanceId) {
        var points = snapshots.findByInstance(instanceId);
        long uploads = points.stream().mapToLong(MetricSnapshotRepository.Snapshot::uploads).sum();
        long duplicates = points.stream().mapToLong(MetricSnapshotRepository.Snapshot::duplicates).sum();
        long physicalBytes = points.stream().mapToLong(MetricSnapshotRepository.Snapshot::physicalBytes).sum();
        long avoidedBytes = points.stream().mapToLong(MetricSnapshotRepository.Snapshot::avoidedBytes).sum();
        long contentCount = points.isEmpty() ? 0 : points.getLast().contentCount();
        return new InstanceMetrics(uploads, duplicates, contentCount, physicalBytes, avoidedBytes);
    }

    private record InstanceMetrics(long uploads, long duplicates, long contentCount, long physicalBytes, long avoidedBytes) { }
}
