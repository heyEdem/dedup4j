package com.edem.blobhelper.reconcile;

import com.edem.dedup4j.core.exception.BlobValidationException;

import java.util.UUID;

/**
 * Difference between an application-owned logical count and Blob Helper's
 * stored physical-content reference count.
 */
public record ReconciliationMismatch(
        UUID assetContentId,
        long expectedReferenceCount,
        long actualReferenceCount
) {

    public ReconciliationMismatch {
        if (assetContentId == null) {
            throw new BlobValidationException("assetContentId must not be null");
        }
        if (expectedReferenceCount < 0) {
            throw new BlobValidationException("expectedReferenceCount must not be negative");
        }
        if (actualReferenceCount < 0) {
            throw new BlobValidationException("actualReferenceCount must not be negative");
        }
    }
}
