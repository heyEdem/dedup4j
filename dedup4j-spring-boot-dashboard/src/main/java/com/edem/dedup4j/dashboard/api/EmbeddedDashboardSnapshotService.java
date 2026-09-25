package com.edem.dedup4j.dashboard.api;

import com.edem.dedup4j.autoconfigure.Dedup4jProperties;
import com.edem.dedup4j.jpa.AssetContent;
import com.edem.dedup4j.jpa.AssetContentRepository;
import com.edem.dedup4j.management.Dedup4jManagementProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

public class EmbeddedDashboardSnapshotService {
    private final MeterRegistry meterRegistry;
    private final AssetContentRepository contentRepository;
    private final Dedup4jProperties dedup4jProperties;
    private final Dedup4jManagementProperties managementProperties;

    public EmbeddedDashboardSnapshotService(
            ObjectProvider<MeterRegistry> meterRegistry,
            ObjectProvider<AssetContentRepository> contentRepository,
            Dedup4jProperties dedup4jProperties,
            Dedup4jManagementProperties managementProperties) {
        this.meterRegistry = meterRegistry.getIfAvailable();
        this.contentRepository = contentRepository.getIfAvailable();
        this.dedup4jProperties = dedup4jProperties;
        this.managementProperties = managementProperties;
    }

    public Snapshot current() {
        List<AssetContent> contents = contentRepository == null ? List.of() : contentRepository.findAll();
        long uploads = counter("dedup4j.uploads");
        long duplicates = counter("dedup4j.duplicates");
        return new Snapshot(
                managementProperties.getInstanceId(), managementProperties.getInstanceName(), provider(),
                uploads, duplicates, counter("dedup4j.skipped.physical.writes"),
                counter("dedup4j.bytes.accepted"), counter("dedup4j.bytes.avoided"),
                contents.size(), contents.stream().mapToLong(AssetContent::getSizeBytes).sum());
    }

    private String provider() {
        String provider = dedup4jProperties.getStorage().getProvider();
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
