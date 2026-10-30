package com.edem.dedup4j.storage.local;

import com.edem.dedup4j.core.exception.BlobStorageException;
import com.edem.dedup4j.core.exception.BlobValidationException;
import com.edem.dedup4j.core.exception.ContentNotFoundException;
import com.edem.dedup4j.core.storage.BlobResource;
import com.edem.dedup4j.core.storage.ListableBlobStorage;
import com.edem.dedup4j.core.storage.ListedBlob;
import com.edem.dedup4j.core.storage.PutBlobRequest;
import com.edem.dedup4j.core.storage.StoredBlob;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.Objects;
import java.util.stream.Stream;

public class LocalBlobStorage implements ListableBlobStorage {

    public static final String PROVIDER = "local";
    private static final String TEMPORARY_FILE_PREFIX = ".dedup4j-";

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

    /**
     * Deletes synchronously in this process, so on a local filesystem the delete cannot take effect
     * after this call returns or throws. A network or custom filesystem behind the root path may not
     * give that guarantee.
     */
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

    /** Walks the directory the prefix names, skipping this adapter's in-progress temporary files. */
    @Override
    public Stream<ListedBlob> list(String keyPrefix) {
        Objects.requireNonNull(keyPrefix, "keyPrefix must not be null");
        Path root = root();
        int lastSlash = keyPrefix.lastIndexOf('/');
        Path start = lastSlash < 0 ? root : root.resolve(keyPrefix.substring(0, lastSlash)).normalize();
        if (!start.startsWith(root)) {
            throw new BlobValidationException("keyPrefix must resolve inside the storage root: " + keyPrefix);
        }
        if (!Files.isDirectory(start)) {
            return Stream.empty();
        }
        try {
            return Files.walk(start)
                    .filter(file -> !file.getFileName().toString().startsWith(TEMPORARY_FILE_PREFIX))
                    .map(file -> listed(root, file))
                    .filter(Objects::nonNull)
                    .filter(blob -> blob.objectKey().startsWith(keyPrefix));
        } catch (IOException failure) {
            throw new BlobStorageException("Failed to list objects under: " + keyPrefix, failure);
        }
    }

    /** Null for directories and for files deleted while the walk was running. */
    private static ListedBlob listed(Path root, Path file) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
            if (!attributes.isRegularFile()) {
                return null;
            }
            String key = root.relativize(file).toString().replace(file.getFileSystem().getSeparator(), "/");
            return new ListedBlob(key, PROVIDER, root.toString(), attributes.size(),
                    attributes.lastModifiedTime().toInstant());
        } catch (NoSuchFileException deleted) {
            return null;
        } catch (IOException failure) {
            throw new BlobStorageException("Failed to read object attributes: " + file, failure);
        }
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
