package com.edem.dedup4j.history;

import com.edem.dedup4j.historystore.UploadContribution;
import com.edem.dedup4j.observability.Dedup4jMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.List;

/**
 * Used when {@code dedup4j.history.enabled=false}. Writes are ignored and totals come from the
 * process-local Micrometer counters, so they reset on restart ("since-start").
 */
public final class DisabledUploadHistory implements UploadHistory {

    private final ObjectProvider<MeterRegistry> registryProvider;

    public DisabledUploadHistory(ObjectProvider<MeterRegistry> registryProvider) {
        this.registryProvider = registryProvider;
    }

    /** Without a registry the since-start totals are all zero. */
    public DisabledUploadHistory() {
        this.registryProvider = null;
    }

    @Override
    public void recordUpload(UploadContribution contribution) {
    }

    @Override
    public void recordFailure(String operation, String contentHash, String objectKey, Throwable failure) {
    }

    @Override
    public HistoryTotals totals() {
        MeterRegistry registry = registryProvider == null ? null : registryProvider.getIfAvailable();
        long uploads = count(registry, Dedup4jMetrics.UPLOADS);
        long skipped = count(registry, Dedup4jMetrics.SKIPPED_PHYSICAL_WRITES);
        return new HistoryTotals("since-start", null, uploads, count(registry, Dedup4jMetrics.DUPLICATES),
                uploads - skipped, count(registry, Dedup4jMetrics.ACCEPTED_BYTES),
                count(registry, Dedup4jMetrics.AVOIDED_BYTES));
    }

    @Override
    public List<FailureRecord> recentFailures(Instant since, int limit) {
        return List.of();
    }

    @Override
    public List<FailureRecord> failuresAfter(long afterSequenceNo, int limit) {
        return List.of();
    }

    @Override
    public boolean persistent() {
        return false;
    }

    private static long count(MeterRegistry registry, String name) {
        if (registry == null) {
            return 0;
        }
        Counter counter = registry.find(name).counter();
        return counter == null ? 0 : (long) counter.count();
    }
}
