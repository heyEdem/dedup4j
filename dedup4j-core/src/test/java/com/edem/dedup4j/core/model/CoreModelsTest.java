package com.edem.dedup4j.core.model;

import com.edem.dedup4j.core.exception.BlobValidationException;
import com.edem.dedup4j.core.hash.ContentHash;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreModelsTest {

    @Test
    void commandCopiesMetadataAndDoesNotAcceptObjectKeys() {
        Map<String, String> metadata = new HashMap<>(Map.of("tenant", "one"));
        StoreBlobCommand command = new StoreBlobCommand(
                new ByteArrayInputStream(new byte[0]),
                "a.txt",
                "text/plain",
                0,
                metadata
        );

        metadata.put("tenant", "two");

        assertEquals("one", command.metadata().get("tenant"));
    }

    @Test
    void referenceCarriesContentIdentityAndDuplicateDecision() {
        ContentHash hash = new ContentHash("SHA-256", "abc", 3);

        BlobReference reference = new BlobReference(
                UUID.randomUUID(),
                hash,
                "text/plain",
                "local",
                "bucket",
                "sha-256/ab/abc",
                true
        );

        assertSame(hash, reference.contentHash());
        assertEquals(new BlobLocation("local", "bucket", "sha-256/ab/abc"), reference.location());
        assertTrue(reference.duplicate());
    }

    @Test
    void rejectsInvalidRequiredModelFields() {
        assertThrows(
                BlobValidationException.class,
                () -> new StoreBlobCommand(InputStream.nullInputStream(), null, null, -1, Map.of())
        );
        assertThrows(
                BlobValidationException.class,
                () -> new BlobReference(
                        null,
                        new ContentHash("SHA-256", "abc", 3),
                        null,
                        "local",
                        "bucket",
                        "key",
                        false
                )
        );
    }
}
