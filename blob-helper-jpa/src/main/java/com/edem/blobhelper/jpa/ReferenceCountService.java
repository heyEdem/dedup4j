package com.edem.blobhelper.jpa;

import com.edem.dedup4j.core.exception.ContentNotFoundException;
import com.edem.dedup4j.core.exception.ReferenceCountUnderflowException;
import com.edem.dedup4j.core.storage.BlobStorage;

import java.util.Objects;
import java.util.UUID;

/**
 * Mutates content reference counts while holding a pessimistic row lock and
 * deletes physical storage after the final reference is released.
 *
 * <p>The caller owns the transaction. The transaction must remain active until
 * the caller commits or rolls back the operation. The supplied storage
 * collaborator is expected to implement the idempotent delete contract from
 * {@code BlobStorage}, so an already-missing object is treated as deleted.</p>
 */
public final class ReferenceCountService {

    private final AssetContentRepository repository;
    private final BlobStorage storage;

    public ReferenceCountService(AssetContentRepository repository, BlobStorage storage) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.storage = Objects.requireNonNull(storage, "storage must not be null");
    }

    public void retain(UUID assetContentId) {
        AssetContent content = findForUpdate(assetContentId);
        content.incrementRefCount();
    }

    public void release(UUID assetContentId) {
        AssetContent content = findForUpdate(assetContentId);
        if (content.getRefCount() <= 0L) {
            throw new ReferenceCountUnderflowException(
                    "Reference count is already zero for asset content " + assetContentId
            );
        }
        content.decrementRefCount();
        if (content.getRefCount() == 0L) {
            storage.delete(content.getObjectKey());
        }
    }

    private AssetContent findForUpdate(UUID assetContentId) {
        Objects.requireNonNull(assetContentId, "assetContentId must not be null");
        return repository.findByIdForUpdate(assetContentId)
                .orElseThrow(() -> new ContentNotFoundException(
                        "Asset content not found: " + assetContentId
                ));
    }
}
