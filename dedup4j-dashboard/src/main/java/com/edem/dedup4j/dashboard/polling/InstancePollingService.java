package com.edem.dedup4j.dashboard.polling;

import com.edem.dedup4j.dashboard.persistence.DatasetTotalsRepository;
import com.edem.dedup4j.dashboard.persistence.FailureEventRepository;
import com.edem.dedup4j.dashboard.persistence.InstanceRepository;
import com.edem.dedup4j.dashboard.persistence.MetricSnapshotRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class InstancePollingService {
    private static final Logger log = LoggerFactory.getLogger(InstancePollingService.class);
    private static final int FAILURE_PAGE_SIZE = 100;
    private static final int FAILURE_PAGES_PER_CYCLE = 10;
    private final InstanceRepository instances;
    private final MetricSnapshotRepository snapshots;
    private final FailureEventRepository failures;
    private final DatasetTotalsRepository totals;
    private final DashboardDatabaseProperties properties;
    private final Clock clock;
    private final HttpClient client = HttpClient.newBuilder().build();
    private final ObjectMapper mapper = new ObjectMapper();
    /** The last cumulative reading per dataset, so replicas of one dataset never multiply trends. */
    private final Map<String, MetricDeltaCalculator.Cumulative> previous = new java.util.concurrent.ConcurrentHashMap<>();

    @Autowired
    public InstancePollingService(InstanceRepository instances, MetricSnapshotRepository snapshots, FailureEventRepository failures,
                                  DatasetTotalsRepository totals, DashboardDatabaseProperties properties) {
        this(instances, snapshots, failures, totals, properties, Clock.systemUTC());
    }

    InstancePollingService(InstanceRepository instances, MetricSnapshotRepository snapshots, FailureEventRepository failures,
                           DatasetTotalsRepository totals, DashboardDatabaseProperties properties, Clock clock) {
        this.instances = instances; this.snapshots = snapshots; this.failures = failures; this.totals = totals;
        this.properties = properties; this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${dedup4j.dashboard.polling-interval:30s}")
    public void pollAll() {
        Set<String> failuresIngested = new HashSet<>();
        for (InstanceRepository.Instance instance : instances.findAll()) poll(instance, failuresIngested);
        failures.deleteOlderThan(clock.instant().minus(properties.failureRetention()));
    }

    private void poll(InstanceRepository.Instance instance, Set<String> failuresIngested) {
        Instant now = clock.instant();
        String base = instance.url().replaceAll("/$", "");
        String datasetId;
        try {
            JsonNode metrics = get(base + "/metrics");
            // Missing (older instance) and null (history disabled) both mean the instance is its own dataset.
            datasetId = metrics.hasNonNull("datasetId") ? text(metrics, "datasetId") : "instance:" + instance.id();
            long uploads = metrics.path("uploads").asLong();
            long skipped = metrics.path("skippedPhysicalWrites").asLong();
            // An older instance reports no physicalWrites; derive it from the skipped writes it does report.
            long physicalWrites = metrics.hasNonNull("physicalWrites") ? metrics.get("physicalWrites").asLong()
                    : metrics.hasNonNull("skippedPhysicalWrites") ? uploads - skipped : 0;
            var current = new MetricDeltaCalculator.Cumulative(uploads, metrics.path("duplicates").asLong(), skipped,
                    metrics.path("acceptedBytes").asLong(), metrics.path("avoidedBytes").asLong(), metrics.path("physicalBytes").asLong());
            long contentCount = metrics.path("contentCount").asLong();
            MetricDeltaCalculator.Delta delta = new MetricDeltaCalculator().calculate(current, previous.put(datasetId, current));
            snapshots.save(new MetricSnapshotRepository.Snapshot(instance.id(), datasetId, now, delta.logicalBytes(), delta.physicalBytes(), delta.avoidedBytes(), delta.uploads(), delta.duplicates(), delta.skippedPhysicalWrites(), contentCount));
            totals.save(new DatasetTotalsRepository.DatasetTotals(datasetId, now, current.uploads(), current.duplicates(), physicalWrites,
                    current.acceptedBytes(), current.avoidedBytes(), contentCount, current.physicalBytes(),
                    metrics.hasNonNull("totalsScope") ? text(metrics, "totalsScope") : null));
            instances.markHealthy(instance.id(), now, datasetId);
        } catch (Exception error) {
            String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
            instances.markFailure(instance.id(), now, message);
            failures.save(new FailureEventRepository.Failure(UUID.randomUUID(), instance.id(), now, "poll", message));
            log.warn("Dashboard poll failed for {}: {}", instance.id(), message);
            return;
        }
        if (failuresIngested.contains(datasetId)) return;
        try {
            ingestFailures(instance, base, datasetId, now);
            failuresIngested.add(datasetId);
        } catch (Exception error) {
            // Another healthy replica of the dataset may still serve its failures this cycle.
            log.warn("Dashboard failure ingestion failed for {}: {}", instance.id(), error.getMessage());
        }
    }

    /** Pages the dataset's failures ascending from its cursor; rows older than the retention window are skipped, not re-requested. */
    private void ingestFailures(InstanceRepository.Instance instance, String base, String datasetId, Instant now) throws Exception {
        Instant cutoff = now.minus(properties.failureRetention());
        long cursor = failures.cursor(datasetId);
        for (int page = 0; page < FAILURE_PAGES_PER_CYCLE; page++) {
            JsonNode items = get(base + "/failures?afterSequence=" + cursor + "&limit=" + FAILURE_PAGE_SIZE);
            if (items.isEmpty()) return;
            for (JsonNode item : items) {
                // An instance without afterSequence support answers without sequence numbers.
                if (!item.hasNonNull("sequenceNo")) { ingestRecentFailures(instance, base, datasetId, cutoff); return; }
            }
            for (JsonNode item : items) {
                store(instance, datasetId, item, cutoff);
                cursor = Math.max(cursor, item.get("sequenceNo").asLong());
            }
            failures.saveCursor(datasetId, cursor);
            if (items.size() < FAILURE_PAGE_SIZE) return;
        }
    }

    /** Best effort for older instances: one {@code since} request per cycle, deduplicated by failure id. */
    private void ingestRecentFailures(InstanceRepository.Instance instance, String base, String datasetId, Instant cutoff) throws Exception {
        for (JsonNode item : get(base + "/failures?since=" + URLEncoder.encode(cutoff.toString(), StandardCharsets.UTF_8))) {
            store(instance, datasetId, item, cutoff);
        }
    }

    private void store(InstanceRepository.Instance instance, String datasetId, JsonNode item, Instant cutoff) {
        Instant occurredAt = Instant.parse(item.get("occurredAt").asString());
        if (occurredAt.isBefore(cutoff)) return;
        // An item without an id cannot be deduplicated; it is stored like a poll failure, under a fresh row id.
        UUID sourceId = item.hasNonNull("id") ? UUID.fromString(item.get("id").asString()) : null;
        String originInstance = item.hasNonNull("instanceId") ? text(item, "instanceId") : instance.id().toString();
        failures.saveApplication(new FailureEventRepository.ApplicationFailure(sourceId, datasetId, instance.id(), originInstance,
                occurredAt, text(item, "operation"), text(item, "message")));
    }

    private static String text(JsonNode node, String field) { return node.hasNonNull(field) ? node.get(field).asString() : ""; }

    private JsonNode get(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(java.time.Duration.ofSeconds(3)).GET().build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 300) throw new IllegalStateException("HTTP " + response.statusCode());
        return mapper.readTree(response.body());
    }
}
