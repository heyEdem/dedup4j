package com.edem.dedup4j.historystore;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "dedup4j_history_dataset")
public class HistoryDataset {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private short id;

    @Column(name = "dataset_id", nullable = false, updatable = false)
    private UUID datasetId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected HistoryDataset() {
    }

    public short getId() {
        return id;
    }

    public UUID getDatasetId() {
        return datasetId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
