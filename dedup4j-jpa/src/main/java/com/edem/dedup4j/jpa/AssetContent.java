package com.edem.dedup4j.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "blob_helper_asset_content",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_blob_helper_asset_content_identity",
                columnNames = {"hash_algorithm", "content_hash", "size_bytes"}
        ),
        indexes = {
                @Index(name = "idx_blob_helper_asset_content_hash", columnList = "content_hash"),
                @Index(name = "idx_blob_helper_asset_content_object_key", columnList = "object_key"),
                @Index(name = "idx_blob_helper_asset_content_ref_count", columnList = "ref_count")
        }
)
public class AssetContent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "hash_algorithm", nullable = false, updatable = false, length = 32)
    private String hashAlgorithm;

    @Column(name = "content_hash", nullable = false, updatable = false, length = 128)
    private String contentHash;

    @Column(name = "size_bytes", nullable = false, updatable = false)
    private long sizeBytes;

    @Column(name = "object_key", nullable = false, updatable = false, length = 1024)
    private String objectKey;

    @Column(name = "storage_provider", nullable = false, updatable = false, length = 64)
    private String storageProvider;

    @Column(name = "bucket_or_container", nullable = false, updatable = false)
    private String bucketOrContainer;

    @Column(name = "content_type")
    private String contentType;

    @Column(name = "original_extension", length = 32)
    private String originalExtension;

    @Column(name = "ref_count", nullable = false)
    private long refCount = 1L;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected AssetContent() {
    }

    public AssetContent(
            String hashAlgorithm,
            String contentHash,
            long sizeBytes,
            String objectKey,
            String storageProvider,
            String bucketOrContainer,
            String contentType,
            String originalExtension
    ) {
        this.hashAlgorithm = requireText(hashAlgorithm, "hashAlgorithm");
        this.contentHash = requireText(contentHash, "contentHash");
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("sizeBytes must not be negative");
        }
        this.sizeBytes = sizeBytes;
        this.objectKey = requireText(objectKey, "objectKey");
        this.storageProvider = requireText(storageProvider, "storageProvider");
        this.bucketOrContainer = requireText(bucketOrContainer, "bucketOrContainer");
        this.contentType = contentType;
        this.originalExtension = originalExtension;
    }

    @PrePersist
    private void initializeTimestamps() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    private void updateTimestamp() {
        updatedAt = Instant.now();
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }

    public UUID getId() {
        return id;
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

    public String getObjectKey() {
        return objectKey;
    }

    public String getStorageProvider() {
        return storageProvider;
    }

    public String getBucketOrContainer() {
        return bucketOrContainer;
    }

    public String getContentType() {
        return contentType;
    }

    public String getOriginalExtension() {
        return originalExtension;
    }

    public long getRefCount() {
        return refCount;
    }

    void incrementRefCount() {
        refCount++;
    }

    void decrementRefCount() {
        refCount--;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Long getVersion() {
        return version;
    }
}
