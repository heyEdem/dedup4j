package com.edem.dedup4j.core.storage;

import com.edem.dedup4j.core.exception.BlobValidationException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StorageModelsTest {

    @Test
    void putRequestCopiesMetadata() {
        Map<String, String> metadata = new HashMap<>(Map.of("owner", "edem"));
        PutBlobRequest request = new PutBlobRequest(
                "sha-256/ab/hash",
                new ByteArrayInputStream(new byte[0]),
                0,
                "text/plain",
                "note.txt",
                metadata
        );

        metadata.put("owner", "changed");

        assertEquals("edem", request.metadata().get("owner"));
    }

    @Test
    void storedBlobCarriesProviderNeutralLocation() {
        Instant createdAt = Instant.parse("2026-07-15T00:00:00Z");

        StoredBlob blob = new StoredBlob(
                "key",
                "s3",
                "bucket",
                12,
                "text/plain",
                "checksum",
                createdAt
        );

        assertEquals("s3", blob.provider());
        assertEquals("bucket", blob.bucketOrContainer());
    }

    @Test
    void resourceClosesOwnedStream() throws Exception {
        TrackingInputStream stream = new TrackingInputStream();
        BlobResource resource = new BlobResource("key", stream, 0, null, null);

        resource.close();

        assertTrue(stream.closed);
        assertTrue(resource.metadata().isEmpty());
    }

    @Test
    void rejectsInvalidRequiredStorageFields() {
        assertThrows(
                BlobValidationException.class,
                () -> new PutBlobRequest(" ", InputStream.nullInputStream(), 0, null, null, Map.of())
        );
        assertThrows(
                BlobValidationException.class,
                () -> new StoredBlob("key", "s3", "bucket", -1, null, null, null)
        );
    }

    private static final class TrackingInputStream extends ByteArrayInputStream {

        private boolean closed;

        private TrackingInputStream() {
            super(new byte[0]);
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }
}
