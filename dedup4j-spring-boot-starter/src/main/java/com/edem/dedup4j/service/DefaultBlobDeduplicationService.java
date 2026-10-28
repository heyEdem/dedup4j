package com.edem.dedup4j.service;

import com.edem.dedup4j.core.exception.ContentNotFoundException;
import com.edem.dedup4j.core.exception.BlobStorageException;
import com.edem.dedup4j.core.hash.ContentHash;
import com.edem.dedup4j.core.hash.ContentHasher;
import com.edem.dedup4j.core.key.ObjectKeyStrategy;
import com.edem.dedup4j.core.model.BlobReference;
import com.edem.dedup4j.core.model.BlobLocation;
import com.edem.dedup4j.core.model.StoreBlobCommand;
import com.edem.dedup4j.core.exception.BlobValidationException;
import com.edem.dedup4j.core.storage.BlobResource;
import com.edem.dedup4j.core.storage.BlobStorage;
import com.edem.dedup4j.core.storage.PutBlobRequest;
import com.edem.dedup4j.core.storage.StoredBlob;
import com.edem.dedup4j.jpa.AssetContent;
import com.edem.dedup4j.jpa.AssetContentMutationService;
import com.edem.dedup4j.jpa.AssetContentRepository;
import com.edem.dedup4j.jpa.ReferenceCountService;
import com.edem.dedup4j.observability.Dedup4jMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Default service facade over metadata and provider-neutral storage
 * collaborators.
 *
 * <p>The caller owns the persistence transaction. Uploads are buffered once
 * so the same bytes can be hashed and replayed to provider-neutral storage.</p>
 */
public final class DefaultBlobDeduplicationService implements BlobDeduplicationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultBlobDeduplicationService.class);
    private static final int HASH_PREFIX_LENGTH = 8;
    public static final long DEFAULT_MAX_UPLOAD_SIZE = 25L * 1024 * 1024;

    private final AssetContentRepository repository;
    private final ReferenceCountService referenceCountService;
    private final AssetContentMutationService mutationService;
    private final BlobStorage storage;
    private final ContentHasher contentHasher;
    private final ObjectKeyStrategy objectKeyStrategy;
    private final Dedup4jMetrics metrics;
    private final long maxUploadSize;
    private final ContentTypePolicy contentTypePolicy;

    public DefaultBlobDeduplicationService(
            AssetContentRepository repository,
            ReferenceCountService referenceCountService,
            AssetContentMutationService mutationService,
            BlobStorage storage,
            ContentHasher contentHasher,
            ObjectKeyStrategy objectKeyStrategy
    ) {
        this(
                repository,
                referenceCountService,
                mutationService,
                storage,
                contentHasher,
                objectKeyStrategy,
                new Dedup4jMetrics(null),
                DEFAULT_MAX_UPLOAD_SIZE
        );
    }

    public DefaultBlobDeduplicationService(
            AssetContentRepository repository,
            ReferenceCountService referenceCountService,
            AssetContentMutationService mutationService,
            BlobStorage storage,
            ContentHasher contentHasher,
            ObjectKeyStrategy objectKeyStrategy,
            long maxUploadSize
    ) {
        this(
                repository,
                referenceCountService,
                mutationService,
                storage,
                contentHasher,
                objectKeyStrategy,
                new Dedup4jMetrics(null),
                maxUploadSize
        );
    }

    public DefaultBlobDeduplicationService(
            AssetContentRepository repository,
            ReferenceCountService referenceCountService,
            AssetContentMutationService mutationService,
            BlobStorage storage,
            ContentHasher contentHasher,
            ObjectKeyStrategy objectKeyStrategy,
            Dedup4jMetrics metrics
    ) {
        this(
                repository,
                referenceCountService,
                mutationService,
                storage,
                contentHasher,
                objectKeyStrategy,
                metrics,
                DEFAULT_MAX_UPLOAD_SIZE
        );
    }

    public DefaultBlobDeduplicationService(
            AssetContentRepository repository,
            ReferenceCountService referenceCountService,
            AssetContentMutationService mutationService,
            BlobStorage storage,
            ContentHasher contentHasher,
            ObjectKeyStrategy objectKeyStrategy,
            Dedup4jMetrics metrics,
            long maxUploadSize
    ) {
        this(
                repository,
                referenceCountService,
                mutationService,
                storage,
                contentHasher,
                objectKeyStrategy,
                metrics,
                maxUploadSize,
                ContentTypePolicy.allowAll()
        );
    }

    public DefaultBlobDeduplicationService(
            AssetContentRepository repository,
            ReferenceCountService referenceCountService,
            AssetContentMutationService mutationService,
            BlobStorage storage,
            ContentHasher contentHasher,
            ObjectKeyStrategy objectKeyStrategy,
            Dedup4jMetrics metrics,
            long maxUploadSize,
            ContentTypePolicy contentTypePolicy
    ) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.referenceCountService = Objects.requireNonNull(
                referenceCountService,
                "referenceCountService must not be null"
        );
        this.mutationService = Objects.requireNonNull(
                mutationService,
                "mutationService must not be null"
        );
        this.storage = Objects.requireNonNull(storage, "storage must not be null");
        this.contentHasher = Objects.requireNonNull(contentHasher, "contentHasher must not be null");
        this.objectKeyStrategy = Objects.requireNonNull(
                objectKeyStrategy,
                "objectKeyStrategy must not be null"
        );
        this.metrics = Objects.requireNonNull(metrics, "metrics must not be null");
        if (maxUploadSize < 0) {
            throw new IllegalArgumentException("maxUploadSize must not be negative");
        }
        this.maxUploadSize = maxUploadSize;
        this.contentTypePolicy = Objects.requireNonNull(
                contentTypePolicy,
                "contentTypePolicy must not be null"
        );
    }

    public long maxUploadSize() {
        return maxUploadSize;
    }

    @Override
    public BlobReference store(StoreBlobCommand command) {
        return storeWithOutcome(command).reference();
    }

    /**
     * Stores like {@link #store} and also reports whether this request wrote to storage.
     * Records no upload meters; the transactional wrapper records them after commit.
     */
    public StoreOutcome storeWithOutcome(StoreBlobCommand command) {
        return storeWithOutcome(command, contentHash -> { });
    }

    /**
     * Like {@link #storeWithOutcome(StoreBlobCommand)}; {@code hashed} receives the content hash
     * as soon as it is computed, so a caller can still report it if the store then fails.
     */
    public StoreOutcome storeWithOutcome(StoreBlobCommand command, Consumer<ContentHash> hashed) {
        Objects.requireNonNull(hashed, "hashed must not be null");
        return storeBuffered(command, buffer(command), hashed);
    }

    /**
     * Reads the upload once, enforcing the allowed content types and the declared and maximum
     * sizes, and closes its stream.
     */
    byte[] buffer(StoreBlobCommand command) {
        Objects.requireNonNull(command, "command must not be null");

        try {
            contentTypePolicy.check(command.contentType());
        } catch (BlobValidationException rejected) {
            closeQuietly(command.content());
            throw rejected;
        }

        if (command.sizeBytes() > maxUploadSize) {
            closeQuietly(command.content());
            throw new BlobValidationException(
                    "Upload size " + command.sizeBytes() + " exceeds maximum " + maxUploadSize
            );
        }

        byte[] bytes = readAll(command.content(), maxUploadSize);
        if (bytes.length != command.sizeBytes()) {
            throw new BlobValidationException(
                    "Declared size " + command.sizeBytes()
                            + " does not match actual size " + bytes.length
            );
        }
        return bytes;
    }

    /**
     * Stores bytes already read by {@link #buffer}; {@code command} supplies only the descriptive
     * fields. Safe to call again with the same bytes after a rolled-back attempt.
     */
    StoreOutcome storeBuffered(StoreBlobCommand command, byte[] bytes, Consumer<ContentHash> hashed) {
        ContentHash contentHash = hash(bytes);
        hashed.accept(contentHash);
        StoreOutcome outcome = repository.findByIdentity(
                        contentHash.algorithm(),
                        contentHash.hash(),
                        contentHash.sizeBytes()
                )
                .map(existing -> retainOrRestore(existing, command, bytes))
                .orElseGet(() -> new StoreOutcome(storeNewContent(command, contentHash, bytes), true));
        logUpload(outcome.reference());
        return outcome;
    }

    private StoreOutcome retainOrRestore(AssetContent existing, StoreBlobCommand command, byte[] bytes) {
        if (referenceCountService.retainIfReferenced(existing.getId())) {
            return new StoreOutcome(BlobReferences.from(existing, true), false);
        }
        // Every reference was released and the object deleted; write it back under the row's key.
        metrics.recordStorageWrite(() -> storage.put(new PutBlobRequest(
                existing.getObjectKey(),
                new ByteArrayInputStream(bytes),
                existing.getSizeBytes(),
                command.contentType(),
                command.filename(),
                command.metadata()
        )));
        referenceCountService.restoreReleased(existing.getId());
        return new StoreOutcome(BlobReferences.from(existing, false), true);
    }

    private BlobReference storeNewContent(
            StoreBlobCommand command,
            ContentHash contentHash,
            byte[] bytes
    ) {
        String objectKey = objectKeyStrategy.generateKey(contentHash);
        StoredBlob stored = metrics.recordStorageWrite(() -> storage.put(new PutBlobRequest(
                    objectKey,
                    new ByteArrayInputStream(bytes),
                    contentHash.sizeBytes(),
                    command.contentType(),
                    command.filename(),
                    command.metadata()
            )));

        AssetContent candidate = new AssetContent(
                contentHash.algorithm(),
                contentHash.hash(),
                contentHash.sizeBytes(),
                stored.objectKey(),
                stored.provider(),
                stored.bucketOrContainer(),
                command.contentType(),
                extractExtension(command.filename())
        );

        AssetContent persisted = mutationService.createOrRetain(candidate);
        if (persisted != candidate) {
            // Another upload committed this content after our lookup, and a release may have deleted
            // the object since our write. The row is locked now, so writing again keeps it present.
            metrics.recordStorageWrite(() -> storage.put(new PutBlobRequest(
                    persisted.getObjectKey(),
                    new ByteArrayInputStream(bytes),
                    contentHash.sizeBytes(),
                    command.contentType(),
                    command.filename(),
                    command.metadata()
            )));
        }
        return BlobReferences.from(persisted, persisted != candidate);
    }

    @Override
    public void retain(UUID assetContentId) {
        referenceCountService.retain(assetContentId);
    }

    /**
     * Releases one reference without touching storage. After the caller's transaction commits,
     * call {@link #deleteReleased} in a new transaction to remove the object of the final reference.
     */
    @Override
    public void release(UUID assetContentId) {
        referenceCountService.release(assetContentId);
    }

    /**
     * Deletes the object of content whose final reference was released and committed, unless it
     * was restored since. Run it in its own transaction. Returns whether it deleted.
     */
    public boolean deleteReleased(UUID assetContentId) {
        AssetContent content = repository.findByIdForUpdate(assetContentId).orElse(null);
        try {
            return referenceCountService.deleteIfReleased(assetContentId);
        } catch (BlobStorageException failure) {
            metrics.recordDeleteFailure();
            logDeleteFailure(assetContentId, content, failure);
            throw failure;
        }
    }

    @Override
    public BlobResource get(UUID assetContentId) {
        Objects.requireNonNull(assetContentId, "assetContentId must not be null");
        AssetContent content = repository.findByIdForUpdate(assetContentId)
                .orElseThrow(() -> new ContentNotFoundException(
                        "Asset content not found: " + assetContentId
                ));
        if (content.getRefCount() <= 0L) {
            // The object may still exist until cleanup deletes it; released content is not readable.
            throw new ContentNotFoundException("Asset content " + assetContentId + " was released");
        }
        return storage.get(content.getObjectKey());
    }

    @Override
    public BlobLocation location(UUID assetContentId) {
        Objects.requireNonNull(assetContentId, "assetContentId must not be null");
        return repository.findById(assetContentId)
                .map(content -> new BlobLocation(
                        content.getStorageProvider(),
                        content.getBucketOrContainer(),
                        content.getObjectKey()
                ))
                .orElseThrow(() -> new ContentNotFoundException(
                        "Asset content not found: " + assetContentId
                ));
    }

    private ContentHash hash(byte[] bytes) {
        return metrics.recordHashing(() -> {
            try {
                return contentHasher.hash(new ByteArrayInputStream(bytes));
            } catch (IOException failure) {
                throw new IllegalStateException("Content hashing failed", failure);
            }
        });
    }

    private static void logUpload(BlobReference reference) {
        LOGGER.info(
                "event=blob.upload contentId={} provider={} objectKey={} decision={} hashPrefix={} sizeBytes={}",
                reference.assetContentId(),
                reference.storageProvider(),
                reference.objectKey(),
                reference.duplicate() ? "duplicate" : "new",
                hashPrefix(reference.contentHash().hash()),
                reference.contentHash().sizeBytes()
        );
    }

    private static void logDeleteFailure(
            UUID assetContentId,
            AssetContent content,
            BlobStorageException failure
    ) {
        LOGGER.error(
                "event=blob.delete.failed contentId={} provider={} objectKey={} errorType={} failureMessage={}",
                assetContentId,
                content == null ? "unknown" : content.getStorageProvider(),
                content == null ? "unknown" : content.getObjectKey(),
                failure.getClass().getSimpleName(),
                failure.getMessage(),
                failure
        );
    }

    private static String hashPrefix(String hash) {
        return hash.substring(0, Math.min(HASH_PREFIX_LENGTH, hash.length()));
    }

    private static byte[] readAll(InputStream stream, long maxUploadSize) {
        Objects.requireNonNull(stream, "content must not be null");
        try (stream) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            long totalRead = 0;
            while (true) {
                // Ask for at most one byte past the limit; adding after the min cannot overflow.
                int toRead = (int) Math.min(buffer.length - 1L, maxUploadSize - totalRead) + 1;
                int read = stream.read(buffer, 0, toRead);
                if (read == -1) {
                    break;
                }
                if (totalRead + read > maxUploadSize) {
                    throw new BlobValidationException("Upload size exceeds maximum " + maxUploadSize);
                }
                output.write(buffer, 0, read);
                totalRead += read;
            }
            return output.toByteArray();
        } catch (IOException failure) {
            throw new IllegalStateException("Reading upload content failed", failure);
        }
    }

    private static void closeQuietly(InputStream content) {
        if (content != null) {
            try {
                content.close();
            } catch (IOException ignored) {
                // The size validation failure is the useful error for the caller.
            }
        }
    }

    private static String extractExtension(String filename) {
        if (filename == null || filename.isBlank()) {
            return null;
        }
        int dotIndex = filename.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == filename.length() - 1) {
            return null;
        }
        String extension = filename.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
        return extension.length() > 32 ? null : extension;
    }
}
