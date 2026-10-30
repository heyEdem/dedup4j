package com.edem.dedup4j.dashboard.api;

import com.edem.dedup4j.autoconfigure.Dedup4jProperties;
import com.edem.dedup4j.history.DisabledUploadHistory;
import com.edem.dedup4j.history.HistoryTotals;
import com.edem.dedup4j.history.UploadHistory;
import com.edem.dedup4j.jpa.AssetContent;
import com.edem.dedup4j.jpa.AssetContentRepository;
import com.edem.dedup4j.management.Dedup4jManagementProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

public class EmbeddedDashboardSnapshotService {
    private final UploadHistory uploadHistory;
    private final AssetContentRepository contentRepository;
    private final Dedup4jProperties dedup4jProperties;
    private final Dedup4jManagementProperties managementProperties;

    public EmbeddedDashboardSnapshotService(
            ObjectProvider<MeterRegistry> meterRegistry,
            ObjectProvider<AssetContentRepository> contentRepository,
            ObjectProvider<UploadHistory> uploadHistory,
            Dedup4jProperties dedup4jProperties,
            Dedup4jManagementProperties managementProperties) {
        this.uploadHistory = uploadHistory.getIfAvailable(() -> new DisabledUploadHistory(meterRegistry));
        this.contentRepository = contentRepository.getIfAvailable();
        this.dedup4jProperties = dedup4jProperties;
        this.managementProperties = managementProperties;
    }

    public Snapshot current() {
        List<AssetContent> contents = contentRepository == null ? List.of() : contentRepository.findAll().stream()
                // Released content keeps its row after its object is deleted; count only what is stored.
                .filter(content -> content.getRefCount() > 0)
                .toList();
        HistoryTotals totals = uploadHistory.totals();
        return new Snapshot(
                managementProperties.getInstanceId(), managementProperties.getInstanceName(), provider(),
                totals.scope(), totals.uploads(), totals.duplicates(), totals.physicalWrites(),
                totals.logicalBytes(), totals.avoidedBytes(),
                contents.size(), contents.stream().mapToLong(AssetContent::getSizeBytes).sum());
    }

    private String provider() {
        String provider = dedup4jProperties.getStorage().getProvider();
        return provider == null || provider.isBlank() ? "unknown" : provider;
    }

    public record Snapshot(String instanceId, String instanceName, String provider,
                           String totalsScope, long uploads, long duplicates, long physicalWrites,
                           long logicalBytes, long avoidedBytes, long contentCount, long physicalBytes) {
        public long newUploads() { return Math.max(0L, uploads - duplicates); }
        public double duplicateRate() { return uploads == 0 ? 0d : (double) duplicates / uploads; }
    }
}
