package com.edem.blobhelper.storage.local;

import com.edem.blobhelper.core.exception.BlobValidationException;
import com.edem.blobhelper.core.exception.ContentNotFoundException;
import com.edem.blobhelper.core.storage.BlobResource;
import com.edem.blobhelper.core.storage.PutBlobRequest;
import com.edem.blobhelper.core.storage.StoredBlob;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalBlobStorageIntegrationTest {

    @TempDir
    Path tempRoot;

    private final LocalBlobStorageProperties properties = new LocalBlobStorageProperties();

    @Test
    void putGetDeleteRoundTrip() throws IOException {
        properties.setRootDirectory(tempRoot);
        LocalBlobStorage storage = new LocalBlobStorage(properties);
        byte[] payload = "round-trip-bytes".getBytes(StandardCharsets.UTF_8);

        StoredBlob stored = storage.put(new PutBlobRequest(
                "sha256/ab/abcdef",
                new ByteArrayInputStream(payload),
                payload.length,
                "text/plain",
                "notes.txt",
                Map.of("origin", "test")
        ));

        assertEquals("sha256/ab/abcdef", stored.objectKey());
        assertEquals(LocalBlobStorage.PROVIDER, stored.provider());
        assertEquals(tempRoot.toString(), stored.bucketOrContainer());
        assertEquals(payload.length, stored.sizeBytes());
        assertEquals("text/plain", stored.contentType());
        assertNotNull(stored.createdAt());
        assertTrue(Files.exists(tempRoot.resolve("sha256/ab/abcdef")));

        assertTrue(storage.exists("sha256/ab/abcdef"));
        try (BlobResource resource = storage.get("sha256/ab/abcdef")) {
            assertEquals("sha256/ab/abcdef", resource.objectKey());
            assertEquals(payload.length, resource.sizeBytes());
            assertArrayEquals(payload, readAll(resource.content()));
        }

        storage.delete("sha256/ab/abcdef");

        assertFalse(storage.exists("sha256/ab/abcdef"));
        assertFalse(Files.exists(tempRoot.resolve("sha256/ab/abcdef")));
        assertThrows(ContentNotFoundException.class, () -> storage.get("sha256/ab/abcdef"));
    }

    @Test
    void missingObjectIsAlreadyDeletedByDefault() {
        properties.setRootDirectory(tempRoot);
        LocalBlobStorage storage = new LocalBlobStorage(properties);

        assertDoesNotThrow(() -> storage.delete("missing/object/key"));        assertFalse(storage.exists("missing/object/key"));
    }

    @Test
    void existsIsFalseBeforeAnyWrite() {
        properties.setRootDirectory(tempRoot);
        LocalBlobStorage storage = new LocalBlobStorage(properties);

        assertFalse(storage.exists("never/written"));
    }

    @Test
    void putOverwritesExistingObject() throws IOException {
        properties.setRootDirectory(tempRoot);
        LocalBlobStorage storage = new LocalBlobStorage(properties);

        storage.put(request("overwrite/key", "first".getBytes(StandardCharsets.UTF_8)));
        storage.put(request("overwrite/key", "second".getBytes(StandardCharsets.UTF_8)));

        try (BlobResource resource = storage.get("overwrite/key")) {
            assertArrayEquals("second".getBytes(StandardCharsets.UTF_8), readAll(resource.content()));
        }
    }

    @Test
    void putSupportsMaximumLengthFilename() throws IOException {
        properties.setRootDirectory(tempRoot);
        LocalBlobStorage storage = new LocalBlobStorage(properties);
        String objectKey = "l".repeat(255);
        byte[] payload = "long-filename-content".getBytes(StandardCharsets.UTF_8);

        storage.put(request(objectKey, payload));

        assertArrayEquals(payload, Files.readAllBytes(tempRoot.resolve(objectKey)));
    }

    @Test
    void concurrentSameKeyPutsFromSeparateInstancesPublishCompleteBytes() throws Exception {
        LocalBlobStorageProperties firstProperties = new LocalBlobStorageProperties();
        firstProperties.setRootDirectory(tempRoot);
        LocalBlobStorageProperties secondProperties = new LocalBlobStorageProperties();
        secondProperties.setRootDirectory(tempRoot);
        LocalBlobStorage firstStorage = new LocalBlobStorage(firstProperties);
        LocalBlobStorage secondStorage = new LocalBlobStorage(secondProperties);
        byte[] firstPayload = "first-concurrent-payload".repeat(4_096)
                .getBytes(StandardCharsets.UTF_8);
        byte[] secondPayload = "second-concurrent-payload".repeat(4_096)
                .getBytes(StandardCharsets.UTF_8);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<StoredBlob> first = executor.submit(() -> {
                await(start);
                return firstStorage.put(request("same/key", firstPayload));
            });
            Future<StoredBlob> second = executor.submit(() -> {
                await(start);
                return secondStorage.put(request("same/key", secondPayload));
            });
            start.countDown();

            assertDoesNotThrow(() -> first.get(10L, TimeUnit.SECONDS));
            assertDoesNotThrow(() -> second.get(10L, TimeUnit.SECONDS));

            byte[] published = Files.readAllBytes(tempRoot.resolve("same/key"));
            assertTrue(java.util.Arrays.equals(firstPayload, published)
                    || java.util.Arrays.equals(secondPayload, published));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void failedPutPreservesExistingTargetAndCleansTemporaryPublication() throws IOException {
        properties.setRootDirectory(tempRoot);
        LocalBlobStorage storage = new LocalBlobStorage(properties);
        byte[] existing = "existing-complete-content".getBytes(StandardCharsets.UTF_8);
        Files.createDirectories(tempRoot.resolve("failure"));
        Files.write(tempRoot.resolve("failure/key"), existing);
        FailingInputStream failingContent = new FailingInputStream(
                "replacement-that-must-not-be-published".getBytes(StandardCharsets.UTF_8));

        assertThrows(com.edem.blobhelper.core.exception.BlobStorageException.class,
                () -> storage.put(new PutBlobRequest(
                        "failure/key", failingContent, 100L,
                        "application/octet-stream", null, null)));

        assertTrue(failingContent.closed);
        assertArrayEquals(existing, Files.readAllBytes(tempRoot.resolve("failure/key")));
        try (var files = Files.list(tempRoot.resolve("failure"))) {
            assertEquals(java.util.List.of(tempRoot.resolve("failure/key")), files.toList());
        }
    }

    @Test
    void rejectsBlankObjectKeys() {
        properties.setRootDirectory(tempRoot);
        LocalBlobStorage storage = new LocalBlobStorage(properties);

        assertThrows(BlobValidationException.class, () -> storage.get(" "));
        assertThrows(BlobValidationException.class, () -> storage.delete(null));
        assertThrows(BlobValidationException.class, () -> storage.exists(" "));
    }

    @Test
    void rejectsParentDirectoryTraversal() throws IOException {
        properties.setRootDirectory(tempRoot);
        LocalBlobStorage storage = new LocalBlobStorage(properties);
        Path outside = Files.createFile(
                tempRoot.getParent().resolve("outside-" + UUID.randomUUID() + ".txt"));
        byte[] outsideBytes = Files.readAllBytes(outside);

        assertThrows(BlobValidationException.class,
                () -> storage.put(request("../escaped.bin", "escape".getBytes(StandardCharsets.UTF_8))));
        assertThrows(BlobValidationException.class, () -> storage.get("../escaped.bin"));
        assertThrows(BlobValidationException.class, () -> storage.delete("../nested/../../escaped.bin"));
        assertThrows(BlobValidationException.class, () -> storage.exists(".."));

        assertFalse(Files.exists(tempRoot.resolve("escaped.bin")));
        assertFalse(Files.exists(outside.getParent().resolve("escaped.bin")));
        assertArrayEquals(outsideBytes, Files.readAllBytes(outside));
    }

    @Test
    void rejectsAbsolutePathKeys() {
        properties.setRootDirectory(tempRoot);
        LocalBlobStorage storage = new LocalBlobStorage(properties);

        assertThrows(BlobValidationException.class,
                () -> storage.put(request("/etc/passwd", "nope".getBytes(StandardCharsets.UTF_8))));
        assertThrows(BlobValidationException.class, () -> storage.get("/etc/passwd"));
        assertThrows(BlobValidationException.class, () -> storage.delete("/etc/passwd"));
        assertThrows(BlobValidationException.class, () -> storage.exists("/etc/passwd"));

        assertFalse(Files.exists(tempRoot.resolve("etc")));
    }

    @Test
    void rejectsSelfResolvingKeys() throws IOException {
        properties.setRootDirectory(tempRoot);
        LocalBlobStorage storage = new LocalBlobStorage(properties);

        assertThrows(BlobValidationException.class, () -> storage.put(
                request("nested/..", "self".getBytes(StandardCharsets.UTF_8))));
        assertThrows(BlobValidationException.class, () -> storage.get("nested/.."));
        assertTrue(Files.isDirectory(tempRoot));
    }

    @Test
    void validNestedKeysStillWork() throws IOException {
        properties.setRootDirectory(tempRoot);
        LocalBlobStorage storage = new LocalBlobStorage(properties);
        byte[] payload = "nested".getBytes(StandardCharsets.UTF_8);

        storage.put(request("deep/nested/key/file.bin", payload));

        assertTrue(storage.exists("deep/nested/key/./file.bin"));
        try (BlobResource resource = storage.get("deep/nested/key/./file.bin")) {
            assertArrayEquals(payload, readAll(resource.content()));
        }
    }

    private PutBlobRequest request(String objectKey, byte[] payload) {
        return new PutBlobRequest(
                objectKey,
                new ByteArrayInputStream(payload),
                payload.length,
                "application/octet-stream",
                null,
                null
        );
    }

    private static byte[] readAll(InputStream stream) {
        try (stream) {
            return stream.readAllBytes();
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static void await(CountDownLatch start) throws InterruptedException {
        if (!start.await(5L, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Timed out waiting for concurrent put start");
        }
    }

    private static final class FailingInputStream extends InputStream {

        private final byte[] bytes;
        private int position;
        private boolean closed;

        private FailingInputStream(byte[] bytes) {
            this.bytes = bytes;
        }

        @Override
        public int read() throws IOException {
            if (position++ >= bytes.length / 2) {
                throw new IOException("simulated source failure");
            }
            return bytes[position - 1] & 0xff;
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
