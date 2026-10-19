package com.edem.dedup4j.history;

import com.edem.dedup4j.historystore.UploadContribution;

import java.time.Instant;
import java.util.List;

/** Persistent record of completed uploads and of upload/delete failures. */
public interface UploadHistory {

    /** Appends one ledger row inside the caller's transaction. */
    void recordUpload(UploadContribution contribution);

    /** Appends a failure row in its own {@code REQUIRES_NEW} transaction. */
    void recordFailure(String operation, String contentHash, String objectKey, Throwable failure);

    HistoryTotals totals();

    /** Newest first; for display only. */
    List<FailureRecord> recentFailures(Instant since, int limit);

    /** Ascending by sequence number; the only supported paging cursor for ingestion. */
    List<FailureRecord> failuresAfter(long afterSequenceNo, int limit);

    boolean persistent();
}
