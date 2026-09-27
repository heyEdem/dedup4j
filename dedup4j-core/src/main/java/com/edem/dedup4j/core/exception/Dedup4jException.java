package com.edem.dedup4j.core.exception;

public class Dedup4jException extends RuntimeException {

    public Dedup4jException(String message) {
        super(message);
    }

    public Dedup4jException(String message, Throwable cause) {
        super(message, cause);
    }
}
