package com.edem.dedup4j.management;

import com.edem.dedup4j.autoconfigure.Dedup4jProperties;
import com.edem.dedup4j.jpa.AssetContent;
import com.edem.dedup4j.jpa.AssetContentRepository;
import com.edem.dedup4j.history.HistoryTotals;
import com.edem.dedup4j.history.UploadHistory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("${dedup4j.management.base-path:/dedup4j/management}/v1")
public class Dedup4jManagementController {

    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 500;

    private final Dedup4jManagementProperties managementProperties;
    private final Dedup4jProperties dedup4jProperties;
    private final UploadHistory uploadHistory;
    private final AssetContentRepository contentRepository;
    private final Dedup4jManagementSnapshot.FailureSource failureSource;

    public Dedup4jManagementController(
            Dedup4jManagementProperties managementProperties,
            Dedup4jProperties dedup4jProperties,
            ObjectProvider<UploadHistory> uploadHistory,
            ObjectProvider<AssetContentRepository> contentRepository,
            ObjectProvider<Dedup4jManagementSnapshot.FailureSource> failureSource
    ) {
        this.managementProperties = managementProperties;
        this.dedup4jProperties = dedup4jProperties;
        this.uploadHistory = uploadHistory.getIfAvailable();
        this.contentRepository = contentRepository.getIfAvailable();
        this.failureSource = failureSource.getIfAvailable(() -> since -> List.of());
    }

    @GetMapping("/info")
    public Dedup4jManagementSnapshot.Info info() {
        return new Dedup4jManagementSnapshot.Info(
                managementProperties.getInstanceId(),
                managementProperties.getInstanceName(),
                provider(),
                totals().datasetId());
    }

    @GetMapping("/health")
    public Dedup4jManagementSnapshot.Health health() {
        return new Dedup4jManagementSnapshot.Health("UP", Instant.now());
    }

    @GetMapping("/metrics")
    public Dedup4jManagementSnapshot.Metrics metrics() {
        List<AssetContent> contents = contentRepository == null ? List.of() : contentRepository.findAll();
        HistoryTotals totals = totals();
        return new Dedup4jManagementSnapshot.Metrics(
                totals.datasetId(),
                totals.scope(),
                totals.uploads(),
                totals.duplicates(),
                totals.physicalWrites(),
                totals.skippedPhysicalWrites(),
                totals.logicalBytes(),
                totals.avoidedBytes(),
                contents.size(),
                contents.stream().mapToLong(AssetContent::getSizeBytes).sum());
    }

    @GetMapping("/failures")
    public List<Dedup4jManagementSnapshot.Failure> failures(
            @RequestParam(name = "since", required = false) Instant since,
            @RequestParam(name = "afterSequence", required = false) Long afterSequence,
            @RequestParam(name = "limit", required = false) Integer limit
    ) {
        int pageSize = limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(limit, MAX_LIMIT));
        if (afterSequence != null) {
            return failureSource.failuresAfter(Math.max(0, afterSequence), pageSize);
        }
        List<Dedup4jManagementSnapshot.Failure> recent = failureSource.recentFailures(since);
        return recent.size() > pageSize ? recent.subList(0, pageSize) : recent;
    }

    private HistoryTotals totals() {
        return uploadHistory == null
                ? new HistoryTotals("since-start", null, 0, 0, 0, 0, 0)
                : uploadHistory.totals();
    }

    private String provider() {
        String provider = dedup4jProperties.getStorage().getProvider();
        return provider == null || provider.isBlank() ? "unknown" : provider;
    }
}
