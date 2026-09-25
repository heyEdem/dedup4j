package com.edem.dedup4j.storage.local;

import com.edem.dedup4j.core.exception.BlobStorageException;
import com.edem.dedup4j.core.exception.BlobValidationException;
import com.edem.dedup4j.core.exception.ContentNotFoundException;
import com.edem.dedup4j.core.storage.BlobResource;
import com.edem.dedup4j.core.storage.BlobStorage;
import com.edem.dedup4j.core.storage.PutBlobRequest;
import com.edem.dedup4j.core.storage.StoredBlob;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;

public class LocalBlobStorage implements BlobStorage {

    public static final String PROVIDER = "local";
    private static final String TEMPORARY_FILE_PREFIX = ".blob-helper-";

    private final LocalBlobStorageProperties properties;

    public LocalBlobStorage(LocalBlobStorageProperties properties) {
        this.properties = properties;
    }

    @Override
    public StoredBlob put(PutBlobRequest request) {
        Path target = resolve(request.objectKey());
        Path temporary = null;
        BlobStorageException publicationFailure = null;
        try {
            Path parent = target.getParent();
            Files.createDirectories(parent);
            temporary = Files.createTempFile(parent, TEMPORARY_FILE_PREFIX, ".tmp");
            try (InputStream content = request.content()) {
                Files.copy(content, temporary, StandardCopyOption.REPLACE_EXISTING);
            }
            try {
                Files.move(temporary, target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException failure) {
            publicationFailure = new BlobStorageException(
                    "Failed to store object: " + request.objectKey(), failure);
            throw publicationFailure;
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException cleanupFailure) {
                    if (publicationFailure != null) {
                        publicationFailure.addSuppressed(cleanupFailure);
                    } else {
                        throw new BlobStorageException(
                                "Failed to clean up temporary object for: " + request.objectKey(),
                                cleanupFailure);
                    }
                }
            }
        }
        return new StoredBlob(
                request.objectKey(),
                PROVIDER,
                root().toString(),
                request.sizeBytes(),
                request.contentType(),
                null,
                Instant.now()
        );
    }

    @Override
    public BlobResource get(String objectKey) {
        Path file = requireExisting(objectKey);
        try {
            return new BlobResource(
                    objectKey,
                    Files.newInputStream(file),
                    Files.size(file),
                    null,
                    null
            );
        } catch (IOException failure) {
            throw new BlobStorageException("Failed to read object: " + objectKey, failure);
        }
    }

    @Override
    public void delete(String objectKey) {
        Path file = resolve(objectKey);
        try {
            Files.deleteIfExists(file);
        } catch (IOException failure) {
            throw new BlobStorageException("Failed to delete object: " + objectKey, failure);
        }
    }

    @Override
    public boolean exists(String objectKey) {
        validateKey(objectKey);
        return Files.exists(resolve(objectKey));
    }

    private Path requireExisting(String objectKey) {
        Path file = resolve(objectKey);
        if (!Files.exists(file)) {
            throw new ContentNotFoundException("Local object not found: " + objectKey);
        }
        return file;
    }

    private Path resolve(String objectKey) {
        validateKey(objectKey);
        Path root = root();
        Path resolved = root.resolve(objectKey).normalize();
        if (resolved.equals(root) || !resolved.startsWith(root)) {
            throw new BlobValidationException(
                    "objectKey must resolve inside the storage root: " + objectKey);
        }
        return resolved;
    }

    private void validateKey(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            throw new BlobValidationException("objectKey must not be blank");
        }
    }

    private Path root() {
        return properties.getRootDirectory().toAbsolutePath().normalize();
    }
}
