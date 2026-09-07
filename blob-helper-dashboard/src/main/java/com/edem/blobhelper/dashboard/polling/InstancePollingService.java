package com.edem.blobhelper.dashboard.polling;

import com.edem.blobhelper.dashboard.persistence.FailureEventRepository;
import com.edem.blobhelper.dashboard.persistence.InstanceRepository;
import com.edem.blobhelper.dashboard.persistence.MetricSnapshotRepository;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class InstancePollingService {
    private static final Logger log = LoggerFactory.getLogger(InstancePollingService.class);
    private final InstanceRepository instances;
    private final MetricSnapshotRepository snapshots;
    private final FailureEventRepository failures;
    private final DashboardDatabaseProperties properties;
    private final HttpClient client = HttpClient.newBuilder().build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<UUID, MetricDeltaCalculator.Cumulative> previous = new java.util.concurrent.ConcurrentHashMap<>();

    public InstancePollingService(InstanceRepository instances, MetricSnapshotRepository snapshots,
                                  FailureEventRepository failures, DashboardDatabaseProperties properties) {
        this.instances = instances; this.snapshots = snapshots; this.failures = failures; this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${blob-helper.dashboard.polling-interval:30s}")
    public void pollAll() {
        for (InstanceRepository.Instance instance : instances.findAll()) poll(instance);
        failures.deleteOlderThan(Instant.now().minus(properties.failureRetention()));
    }

    void poll(InstanceRepository.Instance instance) {
        Instant now = Instant.now();
        try {
            String base = instance.url().replaceAll("/$", "");
            HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/metrics")).timeout(java.time.Duration.ofSeconds(3)).GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) throw new IllegalStateException("HTTP " + response.statusCode());
            Metrics metrics = mapper.readValue(response.body(), Metrics.class);
            MetricDeltaCalculator.Cumulative current = new MetricDeltaCalculator.Cumulative(metrics.uploads, metrics.duplicates, metrics.skippedPhysicalWrites, metrics.acceptedBytes, metrics.avoidedBytes, metrics.physicalBytes);
            MetricDeltaCalculator.Delta delta = new MetricDeltaCalculator().calculate(current, previous.put(instance.id(), current));
            snapshots.save(new MetricSnapshotRepository.Snapshot(instance.id(), now, delta.logicalBytes(), delta.physicalBytes(), delta.avoidedBytes(), delta.uploads(), delta.duplicates(), delta.skippedPhysicalWrites(), metrics.contentCount));
            instances.markHealthy(instance.id(), now);
        } catch (Exception error) {
            String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
            instances.markFailure(instance.id(), now, message);
            failures.save(new FailureEventRepository.Failure(UUID.randomUUID(), instance.id(), now, "poll", message));
            log.warn("Dashboard poll failed for {}: {}", instance.id(), message);
        }
    }

    private static class Metrics { public long uploads, duplicates, skippedPhysicalWrites, acceptedBytes, avoidedBytes, contentCount, physicalBytes; }
}
