package com.edem.dedup4j.jpa;

import com.edem.dedup4j.core.exception.ContentNotFoundException;
import com.edem.dedup4j.core.exception.ReferenceCountUnderflowException;
import com.edem.dedup4j.core.storage.BlobStorage;

import java.util.Objects;
import java.util.UUID;

/**
 * Mutates content reference counts while holding a pessimistic row lock and
 * deletes physical storage once a release of the final reference has committed.
 *
 * <p>The caller owns the transaction. The transaction must remain active until
 * the caller commits or rolls back the operation. Releasing never touches
 * storage: the caller commits the release first and then calls
 * {@link #deleteIfReleased} in a new transaction, so a release that rolls back
 * never loses the object. The supplied storage collaborator is expected to
 * implement the idempotent delete contract from {@code BlobStorage}, so an
 * already-missing object is treated as deleted.</p>
 */
public final class ReferenceCountService {

    private final AssetContentRepository repository;
    private final BlobStorage storage;

    public ReferenceCountService(AssetContentRepository repository, BlobStorage storage) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.storage = Objects.requireNonNull(storage, "storage must not be null");
    }

    public void retain(UUID assetContentId) {
        if (!retainIfReferenced(assetContentId)) {
            throw new ContentNotFoundException(
                    "Asset content " + assetContentId + " was released and its object deleted"
            );
        }
    }

    /**
     * Retains content that still has references. Returns {@code false}, leaving the count at zero,
     * when every reference was released and the physical object deleted.
     */
    public boolean retainIfReferenced(UUID assetContentId) {
        AssetContent content = findForUpdate(assetContentId);
        if (content.getRefCount() <= 0L) {
            return false;
        }
        content.incrementRefCount();
        return true;
    }

    /**
     * Takes released content back from zero to one reference. The caller must lock the row first
     * (for example through {@link #retainIfReferenced}), write the physical object again while
     * holding that lock, and only then restore; writing before locking lets
     * {@link #deleteIfReleased} remove the new object.
     */
    public void restoreReleased(UUID assetContentId) {
        AssetContent content = findForUpdate(assetContentId);
        if (content.getRefCount() != 0L) {
            throw new IllegalStateException(
                    "Asset content " + assetContentId + " is still referenced; nothing to restore"
            );
        }
        content.incrementRefCount();
    }

    /**
     * Releases one reference. Returns {@code true} when this call took the count to zero; the
     * physical object stays until {@link #deleteIfReleased} runs after this transaction commits.
     */
    public boolean release(UUID assetContentId) {
        AssetContent content = findForUpdate(assetContentId);
        if (content.getRefCount() <= 0L) {
            throw new ReferenceCountUnderflowException(
                    "Reference count is already zero for asset content " + assetContentId
            );
        }
        content.decrementRefCount();
        return content.getRefCount() == 0L;
    }

    /**
     * Deletes the physical object of released content. Run it in a new transaction after the
     * release committed, never in the releasing one. It locks the row and deletes only while the
     * count is still zero, so content restored in the meantime keeps its object. Returns whether it
     * deleted. Safe to retry after a storage failure.
     */
    public boolean deleteIfReleased(UUID assetContentId) {
        AssetContent content = findForUpdate(assetContentId);
        if (content.getRefCount() != 0L) {
            return false;
        }
        storage.delete(content.getObjectKey());
        return true;
    }

    private AssetContent findForUpdate(UUID assetContentId) {
        Objects.requireNonNull(assetContentId, "assetContentId must not be null");
        return repository.findByIdForUpdate(assetContentId)
                .orElseThrow(() -> new ContentNotFoundException(
                        "Asset content not found: " + assetContentId
                ));
    }
}
