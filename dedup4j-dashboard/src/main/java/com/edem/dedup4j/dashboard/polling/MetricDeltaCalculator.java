package com.edem.dedup4j.dashboard.polling;

public class MetricDeltaCalculator {
    public record Cumulative(long uploads, long duplicates, long skippedPhysicalWrites, long acceptedBytes,
                             long avoidedBytes, long physicalBytes) {}
    public record Delta(long uploads, long duplicates, long skippedPhysicalWrites, long logicalBytes,
                        long avoidedBytes, long physicalBytes) {}
    public Delta calculate(Cumulative current, Cumulative previous) {
        // The first reading after a dashboard restart is a baseline: its totals were not produced in this interval.
        if (previous == null) return new Delta(0, 0, 0, 0, 0, 0);
        return new Delta(delta(current.uploads(), previous.uploads()), delta(current.duplicates(), previous.duplicates()),
                delta(current.skippedPhysicalWrites(), previous.skippedPhysicalWrites()),
                delta(current.acceptedBytes(), previous.acceptedBytes()), delta(current.avoidedBytes(), previous.avoidedBytes()),
                delta(current.physicalBytes(), previous.physicalBytes()));
    }
    private long delta(long current, long previous) { return current < previous ? 0 : current - previous; }
}
