package com.edem.dedup4j.core.storage;

import com.edem.dedup4j.core.exception.BlobValidationException;

import java.io.InputStream;
import java.util.Map;

public record PutBlobRequest(
        String objectKey,
        InputStream content,
        long sizeBytes,
        String contentType,
        String originalFilename,
        Map<String, String> metadata
) {

    public PutBlobRequest {
        if (objectKey == null || objectKey.isBlank()) {
            throw new BlobValidationException("objectKey must not be blank");
        }
        if (content == null) {
            throw new BlobValidationException("content must not be null");
        }
        if (sizeBytes < 0) {
            throw new BlobValidationException("sizeBytes must not be negative");
        }
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
