package com.edem.dedup4j.management;

import com.edem.dedup4j.history.FailureRecord;
import com.edem.dedup4j.history.UploadHistory;

import java.time.Instant;
import java.util.List;

/** Serves management failures from the persistent {@link UploadHistory}. */
public class UploadHistoryFailureSource implements Dedup4jManagementSnapshot.FailureSource {

    static final int MAX_LIMIT = 500;

    private final UploadHistory history;

    public UploadHistoryFailureSource(UploadHistory history) {
        this.history = history;
    }

    @Override
    public List<Dedup4jManagementSnapshot.Failure> recentFailures(Instant since) {
        return map(history.recentFailures(since == null ? Instant.EPOCH : since, MAX_LIMIT));
    }

    @Override
    public List<Dedup4jManagementSnapshot.Failure> failuresAfter(long afterSequence, int limit) {
        return map(history.failuresAfter(afterSequence, limit));
    }

    private static List<Dedup4jManagementSnapshot.Failure> map(List<FailureRecord> records) {
        return records.stream()
                .map(record -> new Dedup4jManagementSnapshot.Failure(record.id(), record.recordedAt(),
                        record.operation(), record.message(), record.instanceId(),
                        record.contentHash(), record.objectKey(), record.sequenceNo()))
                .toList();
    }
}
