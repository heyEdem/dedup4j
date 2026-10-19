package com.edem.dedup4j.history;

import com.edem.dedup4j.historystore.FailureEvent;
import com.edem.dedup4j.historystore.FailureLogRepository;
import com.edem.dedup4j.historystore.LedgerTotals;
import com.edem.dedup4j.historystore.UploadContribution;
import com.edem.dedup4j.historystore.UploadLedgerRepository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;

public final class JpaUploadHistory implements UploadHistory {

    static final int MAX_MESSAGE_LENGTH = 1000;

    private final UploadLedgerRepository ledger;
    private final FailureLogRepository failures;
    private final String instanceId;
    private final Clock clock;
    private final TransactionTemplate newTransaction;
    private final TransactionTemplate mandatory;
    private final TransactionTemplate readOnly;

    public JpaUploadHistory(
            UploadLedgerRepository ledger,
            FailureLogRepository failures,
            String instanceId,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.ledger = Objects.requireNonNull(ledger, "ledger must not be null");
        this.failures = Objects.requireNonNull(failures, "failures must not be null");
        this.instanceId = Objects.requireNonNull(instanceId, "instanceId must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        Objects.requireNonNull(transactionManager, "transactionManager must not be null");
        this.newTransaction = new TransactionTemplate(transactionManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.mandatory = new TransactionTemplate(transactionManager);
        this.mandatory.setPropagationBehavior(TransactionDefinition.PROPAGATION_MANDATORY);
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
    }

    @Override
    public void recordUpload(UploadContribution contribution) {
        Instant recordedAt = now();
        mandatory.executeWithoutResult(status -> ledger.append(contribution, instanceId, recordedAt));
    }

    @Override
    public void recordFailure(String operation, String contentHash, String objectKey, Throwable failure) {
        Objects.requireNonNull(failure, "failure must not be null");
        String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        if (message.length() > MAX_MESSAGE_LENGTH) {
            message = message.substring(0, MAX_MESSAGE_LENGTH);
        }
        String bounded = message;
        Instant recordedAt = now();
        newTransaction.executeWithoutResult(status ->
                failures.append(instanceId, recordedAt, operation, contentHash, objectKey, bounded));
    }

    @Override
    public HistoryTotals totals() {
        return readOnly.execute(status -> {
            LedgerTotals totals = ledger.latestTotals();
            return new HistoryTotals("all-time", ledger.datasetId().toString(), totals.uploads(),
                    totals.duplicates(), totals.physicalWrites(), totals.logicalBytes(), totals.avoidedBytes());
        });
    }

    @Override
    public List<FailureRecord> recentFailures(Instant since, int limit) {
        return readOnly.execute(status -> failures.recentSince(since, limit).stream()
                .map(JpaUploadHistory::toRecord).toList());
    }

    @Override
    public List<FailureRecord> failuresAfter(long afterSequenceNo, int limit) {
        return readOnly.execute(status -> failures.pageAfter(afterSequenceNo, limit).stream()
                .map(JpaUploadHistory::toRecord).toList());
    }

    @Override
    public boolean persistent() {
        return true;
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static FailureRecord toRecord(FailureEvent e) {
        return new FailureRecord(e.getSequenceNo(), e.getId(), e.getRecordedAt(), e.getOperation(),
                e.getMessage(), e.getInstanceId(), e.getContentHash(), e.getObjectKey());
    }
}
