package com.edem.dedup4j.historystore;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Insert-only access to the failure log. Sequence assignment and commit both happen under the
 * dataset row lock, so paging by {@code sequence_no} can never skip a row.
 *
 * <p>This assumes the caller's transaction runs at READ COMMITTED (the default on PostgreSQL and H2,
 * and pinned by the starter's {@code JpaUploadHistory}). Under REPEATABLE READ a concurrent append
 * fails on the primary key rather than miscounting.</p>
 */
public final class FailureLogRepository {

    private static final short DATASET_ROW_ID = 1;

    private final EntityManager entityManager;

    public FailureLogRepository(EntityManager entityManager) {
        this.entityManager = Objects.requireNonNull(entityManager, "entityManager must not be null");
    }

    public FailureEvent append(
            String instanceId,
            Instant recordedAt,
            String operation,
            String contentHash,
            String objectKey,
            String message) {
        HistoryDataset dataset = entityManager.find(
                HistoryDataset.class, DATASET_ROW_ID, LockModeType.PESSIMISTIC_WRITE);
        Long max = entityManager.createQuery("select max(f.sequenceNo) from FailureEvent f", Long.class)
                .getSingleResult();
        long sequenceNo = max == null ? 1 : max + 1;
        FailureEvent event = new FailureEvent(sequenceNo, UUID.randomUUID(), dataset.getDatasetId(),
                instanceId, recordedAt, operation, contentHash, objectKey, message, null);
        entityManager.persist(event);
        entityManager.flush();
        return event;
    }

    public List<FailureEvent> pageAfter(long afterSequenceNo, int limit) {
        return entityManager.createQuery(
                        "select f from FailureEvent f where f.sequenceNo > :after order by f.sequenceNo asc",
                        FailureEvent.class)
                .setParameter("after", afterSequenceNo)
                .setMaxResults(limit)
                .getResultList();
    }

    /** Newest first by timestamp; for display only, never as a paging cursor. */
    public List<FailureEvent> recentSince(Instant since, int limit) {
        return entityManager.createQuery(
                        "select f from FailureEvent f where f.recordedAt >= :since order by f.recordedAt desc",
                        FailureEvent.class)
                .setParameter("since", since)
                .setMaxResults(limit)
                .getResultList();
    }
}
