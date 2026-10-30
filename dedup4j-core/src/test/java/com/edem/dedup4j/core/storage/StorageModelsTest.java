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
    void contentDispositionKeepsHeadersAsciiForAnyFilename() {
        assertEquals("attachment; filename=\"notes.txt\"; filename*=UTF-8''notes.txt",
                withFilename("notes.txt").contentDisposition());
        // macOS screenshot names put a narrow no-break space (U+202F) before "PM".
        assertEquals("attachment; filename=\"Screenshot at 4.25.10_PM.png\"; "
                        + "filename*=UTF-8''Screenshot%20at%204.25.10%E2%80%AFPM.png",
                withFilename("Screenshot at 4.25.10\u202FPM.png").contentDisposition());
        assertEquals("attachment; filename=\"a_b_c_.txt\"; filename*=UTF-8''a%22b%5Cc%0D.txt",
                withFilename("a\"b\\c\r.txt").contentDisposition());
        assertEquals(null, withFilename(" ").contentDisposition());
        assertEquals(null, withFilename(null).contentDisposition());
    }

    private static PutBlobRequest withFilename(String filename) {
        return new PutBlobRequest("key", new ByteArrayInputStream(new byte[0]), 0, "text/plain", filename, Map.of());
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
