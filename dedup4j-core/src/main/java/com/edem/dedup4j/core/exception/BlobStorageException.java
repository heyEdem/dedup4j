package com.edem.dedup4j.core.exception;

public final class BlobStorageException extends Dedup4jException {

    public BlobStorageException(String message) {
        super(message);
    }

    public BlobStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
