package com.edem.dedup4j.historystore;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "dedup4j_failure_event")
public class FailureEvent {

    @Id
    @Column(name = "sequence_no", nullable = false, updatable = false)
    private long sequenceNo;

    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "dataset_id", nullable = false, updatable = false)
    private UUID datasetId;

    @Column(name = "instance_id", nullable = false, updatable = false, length = 255)
    private String instanceId;

    @Column(name = "recorded_at", nullable = false, updatable = false)
    private Instant recordedAt;

    @Column(name = "operation", nullable = false, updatable = false, length = 16)
    private String operation;

    @Column(name = "content_hash", updatable = false, length = 128)
    private String contentHash;

    @Column(name = "object_key", updatable = false, length = 1024)
    private String objectKey;

    @Column(name = "message", nullable = false, updatable = false, length = 1000)
    private String message;

    @Column(name = "compensates_failure_id", updatable = false)
    private UUID compensatesFailureId;

    protected FailureEvent() {
    }

    public FailureEvent(
            long sequenceNo,
            UUID id,
            UUID datasetId,
            String instanceId,
            Instant recordedAt,
            String operation,
            String contentHash,
            String objectKey,
            String message,
            UUID compensatesFailureId) {
        this.sequenceNo = sequenceNo;
        this.id = id;
        this.datasetId = datasetId;
        this.instanceId = instanceId;
        this.recordedAt = recordedAt;
        this.operation = operation;
        this.contentHash = contentHash;
        this.objectKey = objectKey;
        this.message = message;
        this.compensatesFailureId = compensatesFailureId;
    }

    public long getSequenceNo() {
        return sequenceNo;
    }

    public UUID getId() {
        return id;
    }

    public UUID getDatasetId() {
        return datasetId;
    }

    public String getInstanceId() {
        return instanceId;
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }

    public String getOperation() {
        return operation;
    }

    public String getContentHash() {
        return contentHash;
    }

    public String getObjectKey() {
        return objectKey;
    }

    public String getMessage() {
        return message;
    }

    public UUID getCompensatesFailureId() {
        return compensatesFailureId;
    }
}
