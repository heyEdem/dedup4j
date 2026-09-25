package com.edem.dedup4j.facade;

import com.edem.dedup4j.autoconfigure.Dedup4jProperties;
import com.edem.dedup4j.core.exception.BlobStorageException;
import com.edem.dedup4j.core.exception.BlobValidationException;
import com.edem.dedup4j.core.hash.ContentHash;
import com.edem.dedup4j.core.model.BlobLocation;
import com.edem.dedup4j.core.model.BlobReference;
import com.edem.dedup4j.core.model.StoreBlobCommand;
import com.edem.dedup4j.core.storage.BlobResource;
import com.edem.dedup4j.service.BlobDeduplicationService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultDedup4jBatchTest {

    @Test
    void reportsEveryOutcomeInOrder() {
        RecordingService service = new RecordingService();
        DefaultDedup4j helper = new DefaultDedup4j(service, new Dedup4jProperties());

        BatchStoreResult result = helper.storeAll(new MockMultipartFile[] {
                file("first.txt"), file("broken.txt"), file("duplicate.txt")
        });

        assertEquals(List.of(0, 1, 2), result.outcomes().stream()
                .map(BatchStoreOutcome::index).toList());
        assertTrue(result.outcomes().get(0) instanceof BlobStoreSuccess);
        assertTrue(result.outcomes().get(1) instanceof BlobStoreFailure);
        assertTrue(result.outcomes().get(2) instanceof BlobStoreSuccess);
        assertEquals(2, result.successes().size());
        assertEquals(1, result.failures().size());
        assertEquals("broken.txt", result.failures().getFirst().filename());
        assertEquals(2, result.successes().get(1).index());
        assertTrue(result.successes().get(1).reference().duplicate());
        assertFalse(result.allSucceeded());
        assertEquals(3, service.calls);
    }

    @Test
    void nullItemBecomesFailureAndLaterInputsContinue() {
        RecordingService service = new RecordingService();
        DefaultDedup4j helper = new DefaultDedup4j(service, new Dedup4jProperties());

        BatchStoreResult result = helper.storeAll(new MockMultipartFile[] {
                file("first.txt"), null, file("third.txt")
        });

        assertEquals(3, result.outcomes().size());
        assertTrue(result.outcomes().get(1) instanceof BlobStoreFailure);
        assertEquals(2, result.successes().size());
        assertEquals(2, service.calls);
    }

    @Test
    void nullArrayIsRejectedBeforeProcessing() {
        DefaultDedup4j helper = new DefaultDedup4j(
                new RecordingService(), new Dedup4jProperties()
        );

        assertThrows(BlobValidationException.class, () -> helper.storeAll(null));
    }

    private static MockMultipartFile file(String filename) {
        return new MockMultipartFile("file", filename, "text/plain", filename.getBytes());
    }

    private static final class RecordingService implements BlobDeduplicationService {
        private int calls;

        @Override
        public BlobReference store(StoreBlobCommand command) {
            calls++;
            try {
                command.content().close();
            } catch (IOException failure) {
                throw new AssertionError(failure);
            }
            if ("broken.txt".equals(command.filename())) {
                throw new BlobStorageException("provider failure");
            }
            return new BlobReference(
                    UUID.randomUUID(),
                    new ContentHash("sha-256", command.filename(), command.sizeBytes()),
                    command.contentType(),
                    "local",
                    "bucket",
                    command.filename(),
                    "duplicate.txt".equals(command.filename())
            );
        }

        @Override public void retain(UUID assetContentId) { }
        @Override public void release(UUID assetContentId) { }
        @Override public BlobResource get(UUID assetContentId) { return null; }
        @Override public BlobLocation location(UUID assetContentId) { return new BlobLocation("local", "bucket", "key"); }
    }
}
