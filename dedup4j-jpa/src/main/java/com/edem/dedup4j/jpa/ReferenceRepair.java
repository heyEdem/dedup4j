package com.edem.dedup4j.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One applied reference-count repair, written in the same transaction as the count change.
 * Rows are never updated or deleted; a mistaken repair is answered by a later row whose
 * {@code compensatesRepairId} names it.
 */
@Entity
@Table(name = "dedup4j_reference_repair")
public class ReferenceRepair {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "asset_content_id", nullable = false, updatable = false)
    private UUID assetContentId;

    @Column(name = "previous_count", nullable = false, updatable = false)
    private long previousCount;

    @Column(name = "repaired_count", nullable = false, updatable = false)
    private long repairedCount;

    @Column(name = "run_id", nullable = false, updatable = false)
    private UUID runId;

    @Column(name = "instance_id", nullable = false, updatable = false, length = 255)
    private String instanceId;

    @Column(name = "repaired_at", nullable = false, updatable = false)
    private Instant repairedAt;

    @Column(name = "compensates_repair_id", updatable = false)
    private UUID compensatesRepairId;

    protected ReferenceRepair() {
    }

    ReferenceRepair(
            UUID id,
            UUID assetContentId,
            long previousCount,
            long repairedCount,
            UUID runId,
            String instanceId,
            Instant repairedAt,
            UUID compensatesRepairId) {
        this.id = id;
        this.assetContentId = assetContentId;
        this.previousCount = previousCount;
        this.repairedCount = repairedCount;
        this.runId = runId;
        this.instanceId = instanceId;
        this.repairedAt = repairedAt;
        this.compensatesRepairId = compensatesRepairId;
    }

    public UUID getId() {
        return id;
    }

    public UUID getAssetContentId() {
        return assetContentId;
    }

    public long getPreviousCount() {
        return previousCount;
    }

    public long getRepairedCount() {
        return repairedCount;
    }

    public UUID getRunId() {
        return runId;
    }

    public String getInstanceId() {
        return instanceId;
    }

    public Instant getRepairedAt() {
        return repairedAt;
    }

    public UUID getCompensatesRepairId() {
        return compensatesRepairId;
    }
}
