package com.edem.dedup4j.historystore;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "dedup4j_upload_event")
public class UploadEvent {

    @Id
    @Column(name = "sequence_no", nullable = false, updatable = false)
    private long sequenceNo;

    @Column(name = "dataset_id", nullable = false, updatable = false)
    private UUID datasetId;

    @Column(name = "instance_id", nullable = false, updatable = false, length = 255)
    private String instanceId;

    @Column(name = "recorded_at", nullable = false, updatable = false)
    private Instant recordedAt;

    @Column(name = "asset_content_id", nullable = false, updatable = false)
    private UUID assetContentId;

    @Column(name = "hash_algorithm", nullable = false, updatable = false, length = 32)
    private String hashAlgorithm;

    @Column(name = "content_hash", nullable = false, updatable = false, length = 128)
    private String contentHash;

    @Column(name = "size_bytes", nullable = false, updatable = false)
    private long sizeBytes;

    @Column(name = "upload_delta", nullable = false, updatable = false)
    private long uploadDelta;

    @Column(name = "duplicate_delta", nullable = false, updatable = false)
    private long duplicateDelta;

    @Column(name = "physical_write_delta", nullable = false, updatable = false)
    private long physicalWriteDelta;

    @Column(name = "logical_bytes_delta", nullable = false, updatable = false)
    private long logicalBytesDelta;

    @Column(name = "avoided_bytes_delta", nullable = false, updatable = false)
    private long avoidedBytesDelta;

    @Column(name = "total_uploads", nullable = false, updatable = false)
    private long totalUploads;

    @Column(name = "total_duplicates", nullable = false, updatable = false)
    private long totalDuplicates;

    @Column(name = "total_physical_writes", nullable = false, updatable = false)
    private long totalPhysicalWrites;

    @Column(name = "total_logical_bytes", nullable = false, updatable = false)
    private long totalLogicalBytes;

    @Column(name = "total_avoided_bytes", nullable = false, updatable = false)
    private long totalAvoidedBytes;

    @Column(name = "compensates_sequence_no", updatable = false)
    private Long compensatesSequenceNo;

    protected UploadEvent() {
    }

    public UploadEvent(
            long sequenceNo,
            UUID datasetId,
            String instanceId,
            Instant recordedAt,
            UUID assetContentId,
            String hashAlgorithm,
            String contentHash,
            long sizeBytes,
            long uploadDelta,
            long duplicateDelta,
            long physicalWriteDelta,
            long logicalBytesDelta,
            long avoidedBytesDelta,
            long totalUploads,
            long totalDuplicates,
            long totalPhysicalWrites,
            long totalLogicalBytes,
            long totalAvoidedBytes,
            Long compensatesSequenceNo) {
        this.sequenceNo = sequenceNo;
        this.datasetId = datasetId;
        this.instanceId = instanceId;
        this.recordedAt = recordedAt;
        this.assetContentId = assetContentId;
        this.hashAlgorithm = hashAlgorithm;
        this.contentHash = contentHash;
        this.sizeBytes = sizeBytes;
        this.uploadDelta = uploadDelta;
        this.duplicateDelta = duplicateDelta;
        this.physicalWriteDelta = physicalWriteDelta;
        this.logicalBytesDelta = logicalBytesDelta;
        this.avoidedBytesDelta = avoidedBytesDelta;
        this.totalUploads = totalUploads;
        this.totalDuplicates = totalDuplicates;
        this.totalPhysicalWrites = totalPhysicalWrites;
        this.totalLogicalBytes = totalLogicalBytes;
        this.totalAvoidedBytes = totalAvoidedBytes;
        this.compensatesSequenceNo = compensatesSequenceNo;
    }

    public long getSequenceNo() {
        return sequenceNo;
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

    public UUID getAssetContentId() {
        return assetContentId;
    }

    public String getHashAlgorithm() {
        return hashAlgorithm;
    }

    public String getContentHash() {
        return contentHash;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public long getUploadDelta() {
        return uploadDelta;
    }

    public long getDuplicateDelta() {
        return duplicateDelta;
    }

    public long getPhysicalWriteDelta() {
        return physicalWriteDelta;
    }

    public long getLogicalBytesDelta() {
        return logicalBytesDelta;
    }

    public long getAvoidedBytesDelta() {
        return avoidedBytesDelta;
    }

    public long getTotalUploads() {
        return totalUploads;
    }

    public long getTotalDuplicates() {
        return totalDuplicates;
    }

    public long getTotalPhysicalWrites() {
        return totalPhysicalWrites;
    }

    public long getTotalLogicalBytes() {
        return totalLogicalBytes;
    }

    public long getTotalAvoidedBytes() {
        return totalAvoidedBytes;
    }

    public Long getCompensatesSequenceNo() {
        return compensatesSequenceNo;
    }
}
