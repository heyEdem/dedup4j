package com.edem.blobhelper.service;

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
import com.edem.blobhelper.observability.BlobHelperMetrics;
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
        entityManagerFactory = Persistence.createEntityManagerFactory("blob-helper-starter-test");
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
                new BlobHelperMetrics(metricsRegistry)
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
        BlobReference reference = service.store(new StoreBlobCommand(
                new ByteArrayInputStream(CONTENT),
                "report.txt",
                "text/plain",
                CONTENT.length,
                Map.of("source", "unit-test")
        ));

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
        assertEquals(1.0, metricsRegistry.get("blob.helper.uploads").counter().count());
        assertEquals((double) CONTENT.length,
                metricsRegistry.get("blob.helper.bytes.accepted").counter().count());

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

        BlobReference duplicate = service.store(new StoreBlobCommand(
                new ByteArrayInputStream(CONTENT),
                "copy.txt",
                "text/plain",
                CONTENT.length,
                Map.of("source", "duplicate-upload")
        ));

        assertTrue(duplicate.duplicate());
        assertEquals(original.assetContentId(), duplicate.assetContentId());
        assertEquals(original.contentHash(), duplicate.contentHash());
        assertEquals(original.location(), duplicate.location());
        assertEquals(1, storage.putCount.get());
        assertEquals(2.0, metricsRegistry.get("blob.helper.uploads").counter().count());
        assertEquals(1.0, metricsRegistry.get("blob.helper.duplicates").counter().count());
        assertEquals(1.0, metricsRegistry.get("blob.helper.skipped.physical.writes")
                .counter().count());
        assertEquals((double) CONTENT.length, metricsRegistry.get("blob.helper.bytes.avoided")
                .counter().count());

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
