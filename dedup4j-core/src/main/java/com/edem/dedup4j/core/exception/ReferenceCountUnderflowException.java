package com.edem.dedup4j.core.exception;

public final class ReferenceCountUnderflowException extends Dedup4jException {

    public ReferenceCountUnderflowException(String message) {
        super(message);
    }

    public ReferenceCountUnderflowException(String message, Throwable cause) {
        super(message, cause);
    }
}
