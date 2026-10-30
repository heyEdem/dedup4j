package com.edem.dedup4j.service;

import org.springframework.dao.ConcurrencyFailureException;

/**
 * Another transaction created the same content first. The caller's transaction is marked
 * rollback-only (PostgreSQL has already aborted it); roll it back and run the whole transaction
 * again with the upload's input reopened. The retry finds and retains the other transaction's content.
 */
public final class ContentIdentityConflictException extends ConcurrencyFailureException {

    private final String hashAlgorithm;
    private final String contentHash;
    private final long sizeBytes;

    public ContentIdentityConflictException(String hashAlgorithm, String contentHash, long sizeBytes, Throwable cause) {
        super("Content " + hashAlgorithm + ":" + contentHash + " (" + sizeBytes + " bytes) was created by a "
                + "concurrent transaction; retry the whole transaction", cause);
        this.hashAlgorithm = hashAlgorithm;
        this.contentHash = contentHash;
        this.sizeBytes = sizeBytes;
    }

    public String getHashAlgorithm() {
        return hashAlgorithm;
    }

    public String getContentHash() {
        return contentHash;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }
}
