package com.edem.dedup4j.core.model;

import com.edem.dedup4j.core.exception.BlobValidationException;

import java.io.InputStream;
import java.util.Map;

public record StoreBlobCommand(
        InputStream content,
        String filename,
        String contentType,
        long sizeBytes,
        Map<String, String> metadata
) {

    public StoreBlobCommand {
        if (content == null) {
            throw new BlobValidationException("content must not be null");
        }
        if (sizeBytes < 0) {
            throw new BlobValidationException("sizeBytes must not be negative");
        }
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
