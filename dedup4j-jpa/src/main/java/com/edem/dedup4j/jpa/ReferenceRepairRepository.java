package com.edem.dedup4j.jpa;

import jakarta.persistence.EntityManager;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Insert-only access to the repair audit; there is no update, delete or correction method. */
public final class ReferenceRepairRepository {

    private final EntityManager entityManager;

    public ReferenceRepairRepository(EntityManager entityManager) {
        this.entityManager = Objects.requireNonNull(entityManager, "entityManager must not be null");
    }

    ReferenceRepair append(
            UUID assetContentId,
            long previousCount,
            long repairedCount,
            UUID runId,
            String instanceId,
            Instant repairedAt) {
        ReferenceRepair repair = new ReferenceRepair(UUID.randomUUID(), assetContentId, previousCount,
                repairedCount, runId, instanceId, repairedAt, null);
        entityManager.persist(repair);
        entityManager.flush();
        return repair;
    }

    public List<ReferenceRepair> findByRun(UUID runId) {
        Objects.requireNonNull(runId, "runId must not be null");
        return entityManager.createQuery(
                        "select r from ReferenceRepair r where r.runId = :runId order by r.repairedAt, r.id",
                        ReferenceRepair.class)
                .setParameter("runId", runId)
                .getResultList();
    }

    public List<ReferenceRepair> findByContent(UUID assetContentId) {
        Objects.requireNonNull(assetContentId, "assetContentId must not be null");
        return entityManager.createQuery("""
                                select r from ReferenceRepair r
                                where r.assetContentId = :assetContentId
                                order by r.repairedAt, r.id
                                """, ReferenceRepair.class)
                .setParameter("assetContentId", assetContentId)
                .getResultList();
    }
}
