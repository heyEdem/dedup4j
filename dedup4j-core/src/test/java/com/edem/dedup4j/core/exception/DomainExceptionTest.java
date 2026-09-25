package com.edem.dedup4j.core.exception;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

class DomainExceptionTest {

    @Test
    void domainExceptionsShareBaseTypeAndRetainCause() {
        RuntimeException cause = new RuntimeException("provider failed");
        Dedup4jException exception = new BlobStorageException("put failed", cause);

        assertEquals("put failed", exception.getMessage());
        assertSame(cause, exception.getCause());
        assertInstanceOf(Dedup4jException.class, new BlobValidationException("invalid"));
        assertInstanceOf(Dedup4jException.class, new BlobHashingException("hash failed", cause));
        assertInstanceOf(Dedup4jException.class, new ContentNotFoundException("missing"));
        assertInstanceOf(Dedup4jException.class, new ReferenceCountUnderflowException("underflow"));
    }
}
