package com.edem.blobhelper.facade;

import com.edem.dedup4j.core.exception.BlobValidationException;

import java.util.Objects;

public record BlobStoreFailure(
        int index,
        String filename,
        RuntimeException failure
) implements BatchStoreOutcome {

    public BlobStoreFailure {
        if (index < 0) {
            throw new BlobValidationException("index must not be negative");
        }
        Objects.requireNonNull(failure, "failure must not be null");
    }
}
