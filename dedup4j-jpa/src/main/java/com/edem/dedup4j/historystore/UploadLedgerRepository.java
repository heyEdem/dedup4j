package com.edem.dedup4j.historystore;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Insert-only access to the upload ledger.
 *
 * <p>The entity manager and its transaction belong to the caller. {@link #append} locks the
 * single dataset row, which is held until the caller commits, so each row is built on the
 * latest committed one and no sequence number can be skipped.</p>
 */
public final class UploadLedgerRepository {

    private static final short DATASET_ROW_ID = 1;

    private final EntityManager entityManager;

    public UploadLedgerRepository(EntityManager entityManager) {
        this.entityManager = Objects.requireNonNull(entityManager, "entityManager must not be null");
    }

    public UploadEvent append(UploadContribution c, String instanceId, Instant recordedAt) {
        Objects.requireNonNull(c, "c must not be null");
        HistoryDataset dataset = entityManager.find(
                HistoryDataset.class, DATASET_ROW_ID, LockModeType.PESSIMISTIC_WRITE);
        UploadEvent previous = latest();
        long sequenceNo = previous == null ? 1 : previous.getSequenceNo() + 1;
        LedgerTotals totals = previous == null ? LedgerTotals.ZERO : totalsOf(previous);
        UploadEvent event = new UploadEvent(
                sequenceNo, dataset.getDatasetId(), instanceId, recordedAt,
                c.assetContentId(), c.hashAlgorithm(), c.contentHash(), c.sizeBytes(),
                c.uploadDelta(), c.duplicateDelta(), c.physicalWriteDelta(),
                c.logicalBytesDelta(), c.avoidedBytesDelta(),
                totals.uploads() + c.uploadDelta(),
                totals.duplicates() + c.duplicateDelta(),
                totals.physicalWrites() + c.physicalWriteDelta(),
                totals.logicalBytes() + c.logicalBytesDelta(),
                totals.avoidedBytes() + c.avoidedBytesDelta(),
                c.compensatesSequenceNo());
        entityManager.persist(event);
        entityManager.flush();
        return event;
    }

    public LedgerTotals latestTotals() {
        UploadEvent latest = latest();
        return latest == null ? LedgerTotals.ZERO : totalsOf(latest);
    }

    public UUID datasetId() {
        return entityManager.find(HistoryDataset.class, DATASET_ROW_ID).getDatasetId();
    }

    private UploadEvent latest() {
        return entityManager.createQuery(
                        "select e from UploadEvent e order by e.sequenceNo desc", UploadEvent.class)
                .setMaxResults(1)
                .getResultStream()
                .findFirst()
                .orElse(null);
    }

    private static LedgerTotals totalsOf(UploadEvent e) {
        return new LedgerTotals(e.getTotalUploads(), e.getTotalDuplicates(), e.getTotalPhysicalWrites(),
                e.getTotalLogicalBytes(), e.getTotalAvoidedBytes());
    }
}
