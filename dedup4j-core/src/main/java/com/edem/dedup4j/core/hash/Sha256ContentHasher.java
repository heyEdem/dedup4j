package com.edem.dedup4j.core.hash;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 *
 */
public class Sha256ContentHasher implements ContentHasher {

    private static final String ALGORITHM = "sha-256";
    private static final int BUFFER_SIZE = 8192;

    @Override
    public ContentHash hash(InputStream inputStream) throws IOException {
        Objects.requireNonNull(inputStream, "inputStream must not be null");

        MessageDigest digest = newDigest();
        byte[] buffer = new byte[BUFFER_SIZE];
        long sizeBytes = 0;

        int bytesRead;
        while ((bytesRead = inputStream.read(buffer)) != -1) {
            digest.update(buffer, 0, bytesRead);
            sizeBytes += bytesRead;
        }

        return new ContentHash(ALGORITHM, HexFormat.of().formatHex(digest.digest()), sizeBytes);
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
