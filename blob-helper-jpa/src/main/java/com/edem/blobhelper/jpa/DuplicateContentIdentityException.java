package com.edem.blobhelper.jpa;

/**
 * Signals that another transaction inserted the same physical content
 * identity while this transaction was creating it.
 */
public final class DuplicateContentIdentityException extends RuntimeException {

    private final String hashAlgorithm;
    private final String contentHash;
    private final long sizeBytes;

    public DuplicateContentIdentityException(AssetContent candidate, Throwable cause) {
        super("Concurrent insert for content identity "
                + candidate.getHashAlgorithm() + ":" + candidate.getContentHash()
                + ":" + candidate.getSizeBytes(), cause);
        this.hashAlgorithm = candidate.getHashAlgorithm();
        this.contentHash = candidate.getContentHash();
        this.sizeBytes = candidate.getSizeBytes();
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
