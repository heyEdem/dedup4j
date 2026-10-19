package com.edem.dedup4j.history;

import com.edem.dedup4j.historystore.UploadContribution;

import java.time.Instant;
import java.util.List;

/** Persistent record of completed uploads and of upload/delete failures. */
public interface UploadHistory {

    /**
     * Appends one ledger row. Joins and requires the caller's transaction (fails with
     * {@code IllegalTransactionStateException} otherwise) and holds the dataset lock until
     * that transaction commits. The caller's transaction must run at READ COMMITTED (the auto-configured
     * service pins it); under REPEATABLE READ a concurrent append reuses a sequence number and fails.
     */
    void recordUpload(UploadContribution contribution);

    /**
     * Appends a failure row, committing in its own {@code REQUIRES_NEW} transaction. It needs
     * the dataset lock, so call it only after any transaction that called
     * {@link #recordUpload} has completed; otherwise it waits on that lock (a hang on PostgreSQL).
     */
    void recordFailure(String operation, String contentHash, String objectKey, Throwable failure);

    HistoryTotals totals();

    /** Newest first; for display only. */
    List<FailureRecord> recentFailures(Instant since, int limit);

    /** Ascending by sequence number; the only supported paging cursor for ingestion. */
    List<FailureRecord> failuresAfter(long afterSequenceNo, int limit);

    boolean persistent();
}
