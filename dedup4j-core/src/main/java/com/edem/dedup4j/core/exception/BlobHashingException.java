package com.edem.dedup4j.core.exception;

public final class BlobHashingException extends Dedup4jException {

    public BlobHashingException(String message) {
        super(message);
    }

    public BlobHashingException(String message, Throwable cause) {
        super(message, cause);
    }
}
