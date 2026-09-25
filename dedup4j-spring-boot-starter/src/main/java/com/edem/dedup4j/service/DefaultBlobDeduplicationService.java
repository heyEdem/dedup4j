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
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

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

    private final AssetContentRepository repository;
    private final ReferenceCountService referenceCountService;
    private final AssetContentMutationService mutationService;
    private final BlobStorage storage;
    private final ContentHasher contentHasher;
    private final ObjectKeyStrategy objectKeyStrategy;
    private final Dedup4jMetrics metrics;

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
                new Dedup4jMetrics(null)
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
    }

    @Override
    public BlobReference store(StoreBlobCommand command) {
        Objects.requireNonNull(command, "command must not be null");

        byte[] bytes = readAll(command.content());
        if (bytes.length != command.sizeBytes()) {
            throw new BlobValidationException(
                    "Declared size " + command.sizeBytes()
                            + " does not match actual size " + bytes.length
            );
        }
        ContentHash contentHash = hash(bytes);
        return repository.findByIdentity(
                        contentHash.algorithm(),
                        contentHash.hash(),
                        contentHash.sizeBytes()
                )
                .map(existing -> {
                    metrics.recordUpload(contentHash.sizeBytes(), true);
                    BlobReference reference = retainDuplicate(existing);
                    logUpload(reference);
                    return reference;
                })
                .orElseGet(() -> {
                    metrics.recordUpload(contentHash.sizeBytes(), false);
                    BlobReference reference = storeNewContent(command, contentHash, bytes);
                    logUpload(reference);
                    return reference;
                });
    }

    private BlobReference retainDuplicate(AssetContent existing) {
        referenceCountService.retain(existing.getId());
        return BlobReferences.from(existing, true);
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
        return BlobReferences.from(persisted, persisted != candidate);
    }

    @Override
    public void retain(UUID assetContentId) {
        referenceCountService.retain(assetContentId);
    }

    @Override
    public void release(UUID assetContentId) {
        AssetContent content = repository.findByIdForUpdate(assetContentId).orElse(null);
        try {
            referenceCountService.release(assetContentId);
        } catch (BlobStorageException failure) {
            metrics.recordDeleteFailure();
            logDeleteFailure(assetContentId, content, failure);
            throw failure;
        }
    }

    @Override
    public BlobResource get(UUID assetContentId) {
        Objects.requireNonNull(assetContentId, "assetContentId must not be null");
        return repository.findByIdForUpdate(assetContentId)
                .map(content -> storage.get(content.getObjectKey()))
                .orElseThrow(() -> new ContentNotFoundException(
                        "Asset content not found: " + assetContentId
                ));
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

    private static byte[] readAll(InputStream stream) {
        Objects.requireNonNull(stream, "content must not be null");
        try (stream) {
            return stream.readAllBytes();
        } catch (IOException failure) {
            throw new IllegalStateException("Reading upload content failed", failure);
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
