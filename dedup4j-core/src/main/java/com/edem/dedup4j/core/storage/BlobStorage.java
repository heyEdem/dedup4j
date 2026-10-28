package com.edem.dedup4j.core.storage;

public interface BlobStorage {

    StoredBlob put(PutBlobRequest request);

    BlobResource get(String objectKey);

    /**
     * Deletes the object, treating an already-missing one as deleted. Callers hold the content row
     * lock across this call, but a request the provider applies after the call has failed escapes
     * that lock; an implementation that can should send a condition that only removes the write it
     * meant to remove, so a later rewrite under the same key survives.
     */
    void delete(String objectKey);

    boolean exists(String objectKey);
}
