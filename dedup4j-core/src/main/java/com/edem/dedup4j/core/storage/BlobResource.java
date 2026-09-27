package com.edem.dedup4j.core.storage;

import com.edem.dedup4j.core.exception.BlobValidationException;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

public record BlobResource(
        String objectKey,
        InputStream content,
        long sizeBytes,
        String contentType,
        Map<String, String> metadata
) implements AutoCloseable {

    public BlobResource {
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

    @Override
    public void close() throws IOException {
        content.close();
    }
}
