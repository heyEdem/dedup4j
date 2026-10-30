package com.edem.dedup4j.core.storage;

import com.edem.dedup4j.core.exception.BlobValidationException;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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

    /**
     * The {@code Content-Disposition} header for the original filename, or {@code null} without one.
     * Header values must be ASCII, and signed requests fail on anything else, so the name is sent as
     * an ASCII fallback plus its UTF-8 form (RFC 6266, RFC 8187).
     */
    public String contentDisposition() {
        if (originalFilename == null || originalFilename.isBlank()) {
            return null;
        }
        StringBuilder fallback = new StringBuilder();
        StringBuilder encoded = new StringBuilder();
        originalFilename.codePoints().forEach(codePoint ->
                fallback.append(codePoint >= 0x20 && codePoint < 0x7f && codePoint != '"' && codePoint != '\\'
                        ? (char) codePoint : '_'));
        for (byte b : originalFilename.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xff);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || "!#$&+-.^_`|~".indexOf(c) >= 0) {
                encoded.append(c);
            } else {
                encoded.append('%').append(String.format("%02X", b & 0xff));
            }
        }
        return "attachment; filename=\"" + fallback + "\"; filename*=UTF-8''" + encoded;
    }
}
