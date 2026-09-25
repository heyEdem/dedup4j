package com.edem.blobhelper.management;

import com.edem.blobhelper.autoconfigure.BlobHelperProperties;
import com.edem.dedup4j.jpa.AssetContent;
import com.edem.dedup4j.jpa.AssetContentRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("${blob-helper.management.base-path:/blob-helper/management}/v1")
public class BlobHelperManagementController {

    private final BlobHelperManagementProperties managementProperties;
    private final BlobHelperProperties blobHelperProperties;
    private final MeterRegistry meterRegistry;
    private final AssetContentRepository contentRepository;
    private final BlobHelperManagementSnapshot.FailureSource failureSource;

    public BlobHelperManagementController(
            BlobHelperManagementProperties managementProperties,
            BlobHelperProperties blobHelperProperties,
            ObjectProvider<MeterRegistry> meterRegistry,
            ObjectProvider<AssetContentRepository> contentRepository,
            ObjectProvider<BlobHelperManagementSnapshot.FailureSource> failureSource
    ) {
        this.managementProperties = managementProperties;
        this.blobHelperProperties = blobHelperProperties;
        this.meterRegistry = meterRegistry.getIfAvailable();
        this.contentRepository = contentRepository.getIfAvailable();
        this.failureSource = failureSource.getIfAvailable(() -> since -> List.of());
    }

    @GetMapping("/info")
    public BlobHelperManagementSnapshot.Info info() {
        return new BlobHelperManagementSnapshot.Info(
                managementProperties.getInstanceId(),
                managementProperties.getInstanceName(),
                provider());
    }

    @GetMapping("/health")
    public BlobHelperManagementSnapshot.Health health() {
        return new BlobHelperManagementSnapshot.Health("UP", Instant.now());
    }

    @GetMapping("/metrics")
    public BlobHelperManagementSnapshot.Metrics metrics() {
        List<AssetContent> contents = contentRepository == null ? List.of() : contentRepository.findAll();
        return new BlobHelperManagementSnapshot.Metrics(
                counter("blob.helper.uploads"),
                counter("blob.helper.duplicates"),
                counter("blob.helper.skipped.physical.writes"),
                counter("blob.helper.bytes.accepted"),
                counter("blob.helper.bytes.avoided"),
                contents.size(),
                contents.stream().mapToLong(AssetContent::getSizeBytes).sum());
    }

    @GetMapping("/failures")
    public List<BlobHelperManagementSnapshot.Failure> failures(
            @RequestParam(name = "since", required = false) Instant since
    ) {
        return failureSource.recentFailures(since);
    }

    private String provider() {
        String provider = blobHelperProperties.getStorage().getProvider();
        return provider == null || provider.isBlank() ? "unknown" : provider;
    }

    private long counter(String name) {
        if (meterRegistry == null || meterRegistry.find(name).counter() == null) {
            return 0L;
        }
        return Math.round(meterRegistry.find(name).counter().count());
    }
}
