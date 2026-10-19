package com.edem.dedup4j.dashboard.api;

import com.edem.dedup4j.dashboard.persistence.DatasetTotalsRepository;
import com.edem.dedup4j.dashboard.persistence.FailureEventRepository;
import com.edem.dedup4j.dashboard.persistence.InstanceRepository;
import com.edem.dedup4j.dashboard.persistence.MetricSnapshotRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
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
    private final DatasetTotalsRepository datasetTotals;

    public DashboardController(InstanceRepository instances, MetricSnapshotRepository snapshots, FailureEventRepository failures,
                               DatasetTotalsRepository datasetTotals) {
        this.instances = instances; this.snapshots = snapshots; this.failures = failures; this.datasetTotals = datasetTotals;
    }

    /** Totals count each dataset once, from its most recent poll; the trend is the dataset-keyed deltas. */
    @GetMapping("/overview")
    public DashboardView.Overview overview() {
        var datasets = datasetTotals.findCurrent();
        var registered = instances.findAll();
        long uploads = datasets.stream().mapToLong(DatasetTotalsRepository.DatasetTotals::uploads).sum();
        long duplicates = datasets.stream().mapToLong(DatasetTotalsRepository.DatasetTotals::duplicates).sum();
        long physicalWrites = datasets.stream().mapToLong(DatasetTotalsRepository.DatasetTotals::physicalWrites).sum();
        long logical = datasets.stream().mapToLong(DatasetTotalsRepository.DatasetTotals::logicalBytes).sum();
        long physical = datasets.stream().mapToLong(DatasetTotalsRepository.DatasetTotals::physicalBytes).sum();
        long avoided = datasets.stream().mapToLong(DatasetTotalsRepository.DatasetTotals::avoidedBytes).sum();
        long contentCount = datasets.stream().mapToLong(DatasetTotalsRepository.DatasetTotals::contentCount).sum();
        var trend = snapshots.findAll().stream().map(s -> new DashboardView.TrendPoint(s.observedAt(), s.logicalBytes(), s.physicalBytes(), s.avoidedBytes(), s.uploads(), s.duplicates())).toList();
        return new DashboardView.Overview(registered.size(), registered.stream().filter(i -> "HEALTHY".equals(i.status())).count(), uploads, duplicates, physicalWrites, logical, physical, avoided, uploads == 0 ? 0 : (double) duplicates / uploads, uploads - duplicates, contentCount, trend, scope(datasets));
    }

    /** {@code all-time}, {@code since-start} or {@code mixed} over the counted datasets; null when there are none. */
    private static String scope(List<DatasetTotalsRepository.DatasetTotals> datasets) {
        var scopes = datasets.stream().map(DashboardController::scope).distinct().toList();
        return scopes.isEmpty() ? null : scopes.size() == 1 ? scopes.getFirst() : "mixed";
    }

    /** Instances that report no {@code totalsScope} predate persistent history: their counters reset on restart. */
    private static String scope(DatasetTotalsRepository.DatasetTotals totals) {
        return "all-time".equals(totals.totalsScope()) ? "all-time" : "since-start";
    }

    @GetMapping("/instances/status")
    public List<DashboardView.Instance> instanceStatus() {
        var registered = instances.findAll();
        return registered.stream().map(i -> view(i, registered)).toList();
    }

    @GetMapping("/instances/{id}/history")
    public DashboardView.History history(@PathVariable("id") UUID id) {
        String datasetId = instances.find(id).map(InstanceRepository.Instance::datasetId).orElse(null);
        return new DashboardView.History(id, snapshots.findHistory(id, datasetId).stream().map(s -> new DashboardView.MetricPoint(s.observedAt(), s.logicalBytes(), s.physicalBytes(), s.avoidedBytes(), s.uploads(), s.duplicates(), s.skippedPhysicalWrites(), s.contentCount())).toList());
    }

    @GetMapping("/failures")
    public List<DashboardView.Failure> failures(@RequestParam(name = "since", required = false) Instant since) {
        Instant boundary = since == null ? Instant.now().minus(7, ChronoUnit.DAYS) : since;
        return failures.findSince(boundary).stream().map(f -> new DashboardView.Failure(f.id(), f.instanceId(), f.occurredAt(), f.operation(), f.message(), f.originInstanceId())).toList();
    }

    /** An instance row shows its dataset's figures, flagged when another registered instance reports the same dataset. */
    private DashboardView.Instance view(InstanceRepository.Instance i, List<InstanceRepository.Instance> registered) {
        var totals = i.datasetId() == null ? null : datasetTotals.find(i.datasetId()).orElse(null);
        long uploads = totals == null ? 0 : totals.uploads();
        long duplicates = totals == null ? 0 : totals.duplicates();
        boolean shared = i.datasetId() != null && registered.stream()
                .anyMatch(other -> !other.id().equals(i.id()) && Objects.equals(other.datasetId(), i.datasetId()));
        return new DashboardView.Instance(i.id(), i.name(), i.url(), i.status(), i.registeredAt(), i.lastSeenAt(), i.lastFailureAt(), i.lastFailureMessage(),
                uploads, uploads - duplicates, duplicates, uploads == 0 ? 0 : (double) duplicates / uploads,
                totals == null ? 0 : totals.contentCount(), totals == null ? 0 : totals.physicalBytes(), totals == null ? 0 : totals.avoidedBytes(),
                i.datasetId(), shared, totals == null ? null : scope(totals));
    }
}
