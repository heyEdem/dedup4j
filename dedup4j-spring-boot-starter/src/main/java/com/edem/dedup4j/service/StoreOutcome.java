package com.edem.dedup4j.service;

import com.edem.dedup4j.core.model.BlobReference;

import java.util.Objects;

/**
 * The result of one store and whether this request wrote to storage. A request that wrote to
 * storage and then found the identity already persisted is a duplicate and a physical write.
 */
public record StoreOutcome(BlobReference reference, boolean physicalWrite) {

    public StoreOutcome {
        Objects.requireNonNull(reference, "reference must not be null");
    }
}
