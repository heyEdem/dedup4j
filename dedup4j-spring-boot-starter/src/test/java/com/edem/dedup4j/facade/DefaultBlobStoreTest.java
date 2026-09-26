package com.edem.dedup4j.facade;

import com.edem.dedup4j.autoconfigure.Dedup4jProperties;
import com.edem.dedup4j.core.exception.BlobValidationException;
import com.edem.dedup4j.core.hash.ContentHash;
import com.edem.dedup4j.core.model.BlobReference;
import com.edem.dedup4j.core.model.StoreBlobCommand;
import com.edem.dedup4j.core.storage.BlobResource;
import com.edem.dedup4j.core.storage.BlobStorage;
import com.edem.dedup4j.core.storage.PutBlobRequest;
import com.edem.dedup4j.core.storage.StoredBlob;
import com.edem.dedup4j.service.BlobDeduplicationService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.unit.DataSize;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DefaultBlobStoreTest {

    private final RecordingService service = new RecordingService();
    private final Dedup4jProperties properties = new Dedup4jProperties();
    private final DefaultBlobStore helper = new DefaultBlobStore(service, properties);

    @Test
    void multipartDelegatesOnce() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "photo.png", "image/png", new byte[] {1, 2, 3}
        );

        BlobReference result = helper.store(file);

        assertSame(service.reference, result);
        assertEquals(1, service.commands.size());
        StoreBlobCommand command = service.commands.getFirst();
        assertEquals("photo.png", command.filename());
        assertEquals("image/png", command.contentType());
        assertEquals(3, command.sizeBytes());
        assertEquals(Map.of(), command.metadata());
        assertEquals(List.of(1, 2, 3), readBytes(command));
    }

    @Test
    void acceptsPathBytesAndDescribedStream() throws IOException {
        Path path = Files.createTempFile("dedup4j", ".txt");
        Files.writeString(path, "path-content");
        try {
            helper.store(path);
            helper.store(new byte[] {4, 5}, "bytes.bin", "application/octet-stream");
            Map<String, String> metadata = Map.of("tenant", "acme");
            helper.store(new ByteArrayInputStream(new byte[] {6, 7, 8}), 3,
                    "stream.dat", "application/octet-stream", metadata);

            assertEquals(3, service.commands.size());
            StoreBlobCommand pathCommand = service.commands.get(0);
            assertEquals(path.getFileName().toString(), pathCommand.filename());
            assertEquals(Files.probeContentType(path) == null
                    ? "application/octet-stream" : Files.probeContentType(path), pathCommand.contentType());
            assertEquals(Files.size(path), pathCommand.sizeBytes());

            StoreBlobCommand bytesCommand = service.commands.get(1);
            assertEquals("bytes.bin", bytesCommand.filename());
            assertEquals("application/octet-stream", bytesCommand.contentType());
            assertEquals(2, bytesCommand.sizeBytes());

            StoreBlobCommand streamCommand = service.commands.get(2);
            assertEquals("stream.dat", streamCommand.filename());
            assertEquals("application/octet-stream", streamCommand.contentType());
            assertEquals(3, streamCommand.sizeBytes());
            assertEquals(metadata, streamCommand.metadata());
        } finally {
            Files.deleteIfExists(path);
        }
    }

    @Test
    void rejectsOversizeBeforeDelegate() {
        properties.getDeduplication().setMaxUploadSize(DataSize.ofBytes(2));

        assertThrows(BlobValidationException.class,
                () -> helper.store(new byte[] {1, 2, 3}, "large.bin", "application/octet-stream"));
        assertEquals(0, service.commands.size());
    }

    @Test
    void translatesSourceOpeningFailure() {
        Path missing = Path.of("target", "missing-dedup4j-upload.bin");

        BlobValidationException failure = assertThrows(BlobValidationException.class,
                () -> helper.store(missing));

        assertEquals(0, service.commands.size());
        assertEquals(true, failure.getMessage().contains(missing.toString()));
    }

    private static List<Integer> readBytes(StoreBlobCommand command) {
        try (InputStream content = command.content()) {
            List<Integer> bytes = new ArrayList<>();
            int value;
            while ((value = content.read()) >= 0) {
                bytes.add(value);
            }
            return bytes;
        } catch (IOException failure) {
            throw new AssertionError(failure);
        }
    }

    private static final class RecordingService implements BlobDeduplicationService {
        private final List<StoreBlobCommand> commands = new ArrayList<>();
        private final BlobReference reference = new BlobReference(
                UUID.randomUUID(), new ContentHash("sha-256", "abc", 3),
                "application/octet-stream", "local", "bucket", "key", false
        );

        @Override
        public BlobReference store(StoreBlobCommand command) {
            commands.add(command);
            return reference;
        }

        @Override public void retain(UUID assetContentId) { }
        @Override public void release(UUID assetContentId) { }
        @Override public BlobResource get(UUID assetContentId) { return null; }
        @Override public com.edem.dedup4j.core.model.BlobLocation location(UUID assetContentId) { return reference.location(); }
    }
}
