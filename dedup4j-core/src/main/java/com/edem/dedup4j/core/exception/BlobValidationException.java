package com.edem.dedup4j.core.exception;

public final class BlobValidationException extends Dedup4jException {

    public BlobValidationException(String message) {
        super(message);
    }

    public BlobValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
