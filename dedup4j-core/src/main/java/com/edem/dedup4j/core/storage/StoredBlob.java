package com.edem.dedup4j.core.storage;

import com.edem.dedup4j.core.exception.BlobValidationException;

import java.time.Instant;

public record StoredBlob(
        String objectKey,
        String provider,
        String bucketOrContainer,
        long sizeBytes,
        String contentType,
        String checksum,
        Instant createdAt
) {

    public StoredBlob {
        if (objectKey == null || objectKey.isBlank()) {
            throw new BlobValidationException("objectKey must not be blank");
        }
        if (provider == null || provider.isBlank()) {
            throw new BlobValidationException("provider must not be blank");
        }
        if (bucketOrContainer == null || bucketOrContainer.isBlank()) {
            throw new BlobValidationException("bucketOrContainer must not be blank");
        }
        if (sizeBytes < 0) {
            throw new BlobValidationException("sizeBytes must not be negative");
        }
    }
}
