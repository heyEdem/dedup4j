package com.edem.blobhelper.service;

import com.edem.dedup4j.core.hash.Sha256ContentHasher;
import com.edem.dedup4j.core.key.HashObjectKeyStrategy;
import com.edem.dedup4j.core.model.BlobReference;
import com.edem.dedup4j.core.model.StoreBlobCommand;
import com.edem.dedup4j.core.storage.BlobResource;
import com.edem.dedup4j.core.storage.BlobStorage;
import com.edem.dedup4j.jpa.AssetContentMutationService;
import com.edem.dedup4j.jpa.AssetContentRepository;
import com.edem.dedup4j.jpa.ReferenceCountService;
import com.edem.dedup4j.storage.local.LocalBlobStorage;
import com.edem.dedup4j.storage.local.LocalBlobStorageProperties;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalStorageDeduplicationIntegrationTest {

    private static final byte[] CONTENT = "local-service-integration-content".getBytes(StandardCharsets.UTF_8);

    private static EntityManagerFactory entityManagerFactory;

    @TempDir
    Path storageRoot;

    private EntityManager entityManager;
    private BlobStorage storage;
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

        LocalBlobStorageProperties properties = new LocalBlobStorageProperties();
        properties.setRootDirectory(storageRoot);
        storage = new LocalBlobStorage(properties);

        AssetContentRepository repository = new AssetContentRepository(entityManager);
        service = new DefaultBlobDeduplicationService(
                repository,
                new ReferenceCountService(repository, storage),
                new AssetContentMutationService(entityManager),
                storage,
                new Sha256ContentHasher(),
                new HashObjectKeyStrategy("")
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
    void deduplicatesContentAndDeletesPhysicalObjectAfterFinalRelease() throws IOException {
        BlobReference first = service.store(command("photo.jpg"));

        assertFalse(first.duplicate());
        assertTrue(storage.exists(first.objectKey()));
        try (BlobResource resource = service.get(first.assetContentId())) {
            assertArrayEquals(CONTENT, resource.content().readAllBytes());
        }
        assertEquals(1L, regularFileCount());

        BlobReference duplicate = service.store(command("copy.jpg"));

        assertTrue(duplicate.duplicate());
        assertEquals(first.assetContentId(), duplicate.assetContentId());
        assertEquals(1L, regularFileCount());

        service.release(first.assetContentId());
        assertTrue(storage.exists(first.objectKey()));

        service.release(duplicate.assetContentId());
        assertFalse(storage.exists(first.objectKey()));
        assertEquals(0L, regularFileCount());
    }

    private StoreBlobCommand command(String filename) {
        return new StoreBlobCommand(
                new ByteArrayInputStream(CONTENT),
                filename,
                "text/plain",
                CONTENT.length,
                Map.of("source", "local-integration-test")
        );
    }

    private long regularFileCount() throws IOException {
        try (Stream<Path> paths = Files.walk(storageRoot)) {
            return paths.filter(Files::isRegularFile).count();
        }
    }
}
