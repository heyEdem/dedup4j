package com.edem.dedup4j.core.exception;

public final class ContentNotFoundException extends Dedup4jException {

    public ContentNotFoundException(String message) {
        super(message);
    }

    public ContentNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
