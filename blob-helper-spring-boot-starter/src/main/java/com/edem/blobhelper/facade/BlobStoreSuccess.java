package com.edem.blobhelper.facade;

import com.edem.dedup4j.core.exception.BlobValidationException;
import com.edem.dedup4j.core.model.BlobReference;

import java.util.Objects;

public record BlobStoreSuccess(
        int index,
        String filename,
        BlobReference reference
) implements BatchStoreOutcome {

    public BlobStoreSuccess {
        if (index < 0) {
            throw new BlobValidationException("index must not be negative");
        }
        Objects.requireNonNull(reference, "reference must not be null");
    }
}
