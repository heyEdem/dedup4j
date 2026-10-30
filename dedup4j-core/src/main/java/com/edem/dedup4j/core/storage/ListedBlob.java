package com.edem.dedup4j.core.storage;

import com.edem.dedup4j.core.exception.BlobValidationException;

import java.time.Instant;
import java.util.Objects;

/**
 * An object found by {@link ListableBlobStorage#list}: its key, where it lives, its size and when it
 * was last written.
 */
public record ListedBlob(
        String objectKey,
        String provider,
        String bucketOrContainer,
        long sizeBytes,
        Instant lastModified
) {
    public ListedBlob {
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
        Objects.requireNonNull(lastModified, "lastModified must not be null");
    }
}
