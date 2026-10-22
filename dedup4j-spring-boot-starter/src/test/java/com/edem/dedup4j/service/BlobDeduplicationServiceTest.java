package com.edem.dedup4j.service;

import com.edem.dedup4j.core.hash.ContentHasher;
import com.edem.dedup4j.core.hash.Sha256ContentHasher;
import com.edem.dedup4j.core.exception.BlobValidationException;
import com.edem.dedup4j.core.key.HashObjectKeyStrategy;
import com.edem.dedup4j.core.model.BlobReference;
import com.edem.dedup4j.core.model.StoreBlobCommand;
import com.edem.dedup4j.core.storage.BlobResource;
import com.edem.dedup4j.core.storage.BlobStorage;
import com.edem.dedup4j.core.storage.PutBlobRequest;
import com.edem.dedup4j.core.storage.StoredBlob;
import com.edem.dedup4j.jpa.AssetContent;
import com.edem.dedup4j.jpa.AssetContentMutationService;
import com.edem.dedup4j.jpa.AssetContentRepository;
import com.edem.dedup4j.jpa.ReferenceCountService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;
import com.edem.dedup4j.observability.Dedup4jMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlobDeduplicationServiceTest {

    private static final byte[] CONTENT = "unique-new-content-bytes".getBytes(StandardCharsets.UTF_8);

    private static EntityManagerFactory entityManagerFactory;

    private EntityManager entityManager;
    private RecordingBlobStorage storage;
    private SimpleMeterRegistry metricsRegistry;
    private DefaultBlobDeduplicationService service;

    @BeforeAll
    static void createEntityManagerFactory() {
        entityManagerFactory = Persistence.createEntityManagerFactory("dedup4j-starter-test");
    }

    @AfterAll
    static void closeEntityManagerFactory() {
        if (entityManagerFactory != null) {
            entityManagerFactory.close();
        }
    }

    @BeforeEach
    void setUp() {
        entityManager = entityManagerFactory.createEntityManager();
        entityManager.getTransaction().begin();
        storage = new RecordingBlobStorage();
        metricsRegistry = new SimpleMeterRegistry();
        ContentHasher hasher = new Sha256ContentHasher();
        AssetContentRepository repository = new AssetContentRepository(entityManager);
        service = new DefaultBlobDeduplicationService(
                repository,
                new ReferenceCountService(repository, storage),
                new AssetContentMutationService(entityManager),
                storage,
                hasher,
                new HashObjectKeyStrategy(""),
                new Dedup4jMetrics(metricsRegistry)
        );
    }

    @AfterEach
    void tearDown() {
        if (entityManager.getTransaction().isActive()) {
            entityManager.getTransaction().rollback();
        }
        entityManager.close();
    }

    @Test
    void storesNewContent() {
        StoreOutcome outcome = service.storeWithOutcome(new StoreBlobCommand(
                new ByteArrayInputStream(CONTENT),
                "report.txt",
                "text/plain",
                CONTENT.length,
                Map.of("source", "unit-test")
        ));

        BlobReference reference = outcome.reference();
        assertTrue(outcome.physicalWrite());
        assertFalse(reference.duplicate());
        assertEquals("sha-256", reference.contentHash().algorithm());
        assertEquals(CONTENT.length, reference.contentHash().sizeBytes());
        assertEquals("sha-256/" + reference.contentHash().hash().substring(0, 2)
                        + "/" + reference.contentHash().hash(),
                reference.objectKey());
        assertEquals(storage.lastStored.provider(), reference.storageProvider());
        assertEquals("test-bucket", reference.bucketOrContainer());
        assertEquals(reference.location(), service.location(reference.assetContentId()));

        assertEquals(1, storage.putCount.get());
        assertNull(metricsRegistry.find("dedup4j.uploads").counter());

        Long rowCount = entityManager.createQuery(
                        "select count(content) from AssetContent content", Long.class)
                .getSingleResult();
        assertEquals(1L, rowCount);

        AssetContent persisted = entityManager.find(AssetContent.class, reference.assetContentId());
        assertEquals(1L, persisted.getRefCount());
        assertEquals("text/plain", persisted.getContentType());
        assertEquals("txt", persisted.getOriginalExtension());
    }

    @Test
    void reusesDuplicateContent() {
        BlobReference original = service.store(new StoreBlobCommand(
                new ByteArrayInputStream(CONTENT),
                "report.txt",
                "text/plain",
                CONTENT.length,
                Map.of("source", "first-upload")
        ));

        StoreOutcome duplicateOutcome = service.storeWithOutcome(new StoreBlobCommand(
                new ByteArrayInputStream(CONTENT),
                "copy.txt",
                "text/plain",
                CONTENT.length,
                Map.of("source", "duplicate-upload")
        ));

        BlobReference duplicate = duplicateOutcome.reference();
        assertFalse(duplicateOutcome.physicalWrite());
        assertTrue(duplicate.duplicate());
        assertEquals(original.assetContentId(), duplicate.assetContentId());
        assertEquals(original.contentHash(), duplicate.contentHash());
        assertEquals(original.location(), duplicate.location());
        assertEquals(1, storage.putCount.get());
        assertNull(metricsRegistry.find("dedup4j.uploads").counter());

        AssetContent persisted = entityManager.find(AssetContent.class, original.assetContentId());
        assertEquals(2L, persisted.getRefCount());

        Long rowCount = entityManager.createQuery(
                        "select count(content) from AssetContent content", Long.class)
                .getSingleResult();
        assertEquals(1L, rowCount);
    }

    @Test
    void rejectsDeclaredSizeMismatch() {
        assertThrows(BlobValidationException.class, () -> service.store(new StoreBlobCommand(
                new ByteArrayInputStream(CONTENT),
                "report.txt",
                "text/plain",
                CONTENT.length - 1,
                Map.of()
        )));

        assertEquals(0, storage.putCount.get());
    }

    @Test
    void rejectsDeclaredSizeExceedingMaxUploadSize() {
        DefaultBlobDeduplicationService limitedService = new DefaultBlobDeduplicationService(
                new AssetContentRepository(entityManager),
                new ReferenceCountService(new AssetContentRepository(entityManager), storage),
                new AssetContentMutationService(entityManager),
                storage,
                new Sha256ContentHasher(),
                new HashObjectKeyStrategy(""),
                new Dedup4jMetrics(metricsRegistry),
                10L
        );

        TrackingInputStream stream = new TrackingInputStream(new byte[20]);
        BlobValidationException ex = assertThrows(BlobValidationException.class, () -> limitedService.store(new StoreBlobCommand(
                stream,
                "oversized.txt",
                "text/plain",
                20L,
                Map.of()
        )));

        assertTrue(ex.getMessage().contains("exceeds maximum 10"));
        assertEquals(0, stream.bytesRead);
        assertTrue(stream.closed);
        assertEquals(0, storage.putCount.get());
        Long rowCount = entityManager.createQuery(
                "select count(content) from AssetContent content", Long.class)
                .getSingleResult();
        assertEquals(0L, rowCount);
    }

    @Test
    void rejectsStreamExceedingMaxUploadSizeWithoutBufferingBeyondLimit() {
        DefaultBlobDeduplicationService limitedService = new DefaultBlobDeduplicationService(
                new AssetContentRepository(entityManager),
                new ReferenceCountService(new AssetContentRepository(entityManager), storage),
                new AssetContentMutationService(entityManager),
                storage,
                new Sha256ContentHasher(),
                new HashObjectKeyStrategy(""),
                new Dedup4jMetrics(metricsRegistry),
                10L
        );

        // Declared size is 10 (within limit), but actual stream has 100 bytes
        TrackingInputStream stream = new TrackingInputStream(new byte[100]);
        BlobValidationException ex = assertThrows(BlobValidationException.class, () -> limitedService.store(new StoreBlobCommand(
                stream,
                "lying-size.txt",
                "text/plain",
                10L,
                Map.of()
        )));

        assertTrue(ex.getMessage().contains("exceeds maximum 10"));
        // Rejection happens as soon as the limit is exceeded (at 10 + 1 = 11 bytes), without buffering all 100 bytes!
        assertEquals(11, stream.bytesRead);
        assertTrue(stream.closed);
        assertEquals(0, storage.putCount.get());
        Long rowCount = entityManager.createQuery(
                "select count(content) from AssetContent content", Long.class)
                .getSingleResult();
        assertEquals(0L, rowCount);
    }

    @Test
    void storesUploadOfExactlyMaxUploadSize() {
        DefaultBlobDeduplicationService limitedService = serviceWithMaxUploadSize(10L);

        BlobReference reference = limitedService.store(new StoreBlobCommand(
                new ByteArrayInputStream(new byte[10]),
                "exact.bin",
                "application/octet-stream",
                10L,
                Map.of()
        ));

        assertEquals(10L, reference.contentHash().sizeBytes());
        assertEquals(1, storage.putCount.get());
    }

    @Test
    void largestMaxUploadSizeStillAcceptsUploads() {
        DefaultBlobDeduplicationService unlimitedService = serviceWithMaxUploadSize(Long.MAX_VALUE);

        BlobReference reference = unlimitedService.store(new StoreBlobCommand(
                new ByteArrayInputStream("small".getBytes(StandardCharsets.UTF_8)),
                "small.txt",
                "text/plain",
                5L,
                Map.of()
        ));

        assertEquals(5L, reference.contentHash().sizeBytes());
        assertEquals(1, storage.putCount.get());
    }

    private DefaultBlobDeduplicationService serviceWithMaxUploadSize(long maxUploadSize) {
        AssetContentRepository repository = new AssetContentRepository(entityManager);
        return new DefaultBlobDeduplicationService(
                repository,
                new ReferenceCountService(repository, storage),
                new AssetContentMutationService(entityManager),
                storage,
                new Sha256ContentHasher(),
                new HashObjectKeyStrategy(""),
                new Dedup4jMetrics(metricsRegistry),
                maxUploadSize
        );
    }

    private static final class TrackingInputStream extends InputStream {
        private final byte[] data;
        private int index = 0;
        private int bytesRead = 0;
        private boolean closed = false;

        TrackingInputStream(byte[] data) {
            this.data = data;
        }

        @Override
        public int read() {
            if (index >= data.length) {
                return -1;
            }
            bytesRead++;
            return data[index++] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            if (index >= data.length) {
                return -1;
            }
            int available = Math.min(len, data.length - index);
            System.arraycopy(data, index, b, off, available);
            index += available;
            bytesRead += available;
            return available;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static final class RecordingBlobStorage implements BlobStorage {

        private final AtomicInteger putCount = new AtomicInteger();
        private volatile StoredBlob lastStored;

        @Override
        public StoredBlob put(PutBlobRequest request) {
            putCount.incrementAndGet();
            StoredBlob stored = new StoredBlob(
                    request.objectKey(),
                    "recording",
                    "test-bucket",
                    request.sizeBytes(),
                    request.contentType(),
                    null,
                    Instant.now()
            );
            lastStored = stored;
            return stored;
        }

        @Override
        public BlobResource get(String objectKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void delete(String objectKey) {
        }

        @Override
        public boolean exists(String objectKey) {
            return false;
        }
    }
}
