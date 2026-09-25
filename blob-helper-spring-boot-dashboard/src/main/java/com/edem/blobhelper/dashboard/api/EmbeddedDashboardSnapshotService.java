package com.edem.blobhelper.dashboard.api;

import com.edem.blobhelper.autoconfigure.BlobHelperProperties;
import com.edem.dedup4j.jpa.AssetContent;
import com.edem.dedup4j.jpa.AssetContentRepository;
import com.edem.blobhelper.management.BlobHelperManagementProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

public class EmbeddedDashboardSnapshotService {
    private final MeterRegistry meterRegistry;
    private final AssetContentRepository contentRepository;
    private final BlobHelperProperties blobHelperProperties;
    private final BlobHelperManagementProperties managementProperties;

    public EmbeddedDashboardSnapshotService(
            ObjectProvider<MeterRegistry> meterRegistry,
            ObjectProvider<AssetContentRepository> contentRepository,
            BlobHelperProperties blobHelperProperties,
            BlobHelperManagementProperties managementProperties) {
        this.meterRegistry = meterRegistry.getIfAvailable();
        this.contentRepository = contentRepository.getIfAvailable();
        this.blobHelperProperties = blobHelperProperties;
        this.managementProperties = managementProperties;
    }

    public Snapshot current() {
        List<AssetContent> contents = contentRepository == null ? List.of() : contentRepository.findAll();
        long uploads = counter("blob.helper.uploads");
        long duplicates = counter("blob.helper.duplicates");
        return new Snapshot(
                managementProperties.getInstanceId(), managementProperties.getInstanceName(), provider(),
                uploads, duplicates, counter("blob.helper.skipped.physical.writes"),
                counter("blob.helper.bytes.accepted"), counter("blob.helper.bytes.avoided"),
                contents.size(), contents.stream().mapToLong(AssetContent::getSizeBytes).sum());
    }

    private String provider() {
        String provider = blobHelperProperties.getStorage().getProvider();
        return provider == null || provider.isBlank() ? "unknown" : provider;
    }

    private long counter(String name) {
        if (meterRegistry == null || meterRegistry.find(name).counter() == null) return 0L;
        return Math.round(meterRegistry.find(name).counter().count());
    }

    public record Snapshot(String instanceId, String instanceName, String provider,
                           long uploads, long duplicates, long physicalUploads,
                           long logicalBytes, long avoidedBytes, long contentCount, long physicalBytes) {
        public long newUploads() { return Math.max(0L, uploads - duplicates); }
        public double duplicateRate() { return uploads == 0 ? 0d : (double) duplicates / uploads; }
    }
}
