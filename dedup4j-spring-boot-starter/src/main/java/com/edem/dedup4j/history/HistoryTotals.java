package com.edem.dedup4j.history;

/**
 * Upload totals. {@code scope} is {@code "all-time"} (persistent ledger) or {@code "since-start"}
 * (process-local counters); {@code datasetId} is null when the totals are not tied to a dataset.
 */
public record HistoryTotals(
        String scope,
        String datasetId,
        long uploads,
        long duplicates,
        long physicalWrites,
        long logicalBytes,
        long avoidedBytes) {

    public long newUploads() {
        return uploads - duplicates;
    }

    public long skippedPhysicalWrites() {
        return uploads - physicalWrites;
    }
}
