package com.edem.dedup4j.historystore;

public record LedgerTotals(long uploads, long duplicates, long physicalWrites, long logicalBytes, long avoidedBytes) {

    public static final LedgerTotals ZERO = new LedgerTotals(0, 0, 0, 0, 0);
}
