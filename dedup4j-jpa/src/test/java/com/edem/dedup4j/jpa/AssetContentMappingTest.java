package com.edem.dedup4j.jpa;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Index;
import jakarta.persistence.Persistence;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AssetContentMappingTest {

    private static EntityManagerFactory entityManagerFactory;

    private EntityManager entityManager;

    @BeforeAll
    static void createEntityManagerFactory() {
        entityManagerFactory = Persistence.createEntityManagerFactory("dedup4j-jpa-test");
    }

    @AfterAll
    static void closeEntityManagerFactory() {
        entityManagerFactory.close();
    }

    @BeforeEach
    void createEntityManager() {
        entityManager = entityManagerFactory.createEntityManager();
    }

    @AfterEach
    void closeEntityManager() {
        if (entityManager.getTransaction().isActive()) {
            entityManager.getTransaction().rollback();
        }
        entityManager.close();
    }

    @Test
    void persistsPhysicalBlobMetadataWithGeneratedState() {
        String contentHash = "a".repeat(64);
        AssetContent content = new AssetContent(
                "SHA-256",
                contentHash,
                42L,
                "uploads/SHA-256/aa/" + contentHash,
                "local",
                "test-bucket",
                "application/octet-stream",
                "bin"
        );

        entityManager.getTransaction().begin();
        entityManager.persist(content);
        entityManager.getTransaction().commit();
        entityManager.clear();

        AssetContent persisted = entityManager.find(AssetContent.class, content.getId());

        assertAll(
                () -> assertNotNull(persisted),
                () -> assertNotNull(persisted.getId()),
                () -> assertEquals("SHA-256", persisted.getHashAlgorithm()),
                () -> assertEquals(contentHash, persisted.getContentHash()),
                () -> assertEquals(42L, persisted.getSizeBytes()),
                () -> assertEquals("uploads/SHA-256/aa/" + contentHash, persisted.getObjectKey()),
                () -> assertEquals("local", persisted.getStorageProvider()),
                () -> assertEquals("test-bucket", persisted.getBucketOrContainer()),
                () -> assertEquals("application/octet-stream", persisted.getContentType()),
                () -> assertEquals("bin", persisted.getOriginalExtension()),
                () -> assertEquals(1L, persisted.getRefCount()),
                () -> assertNotNull(persisted.getCreatedAt()),
                () -> assertEquals(persisted.getCreatedAt(), persisted.getUpdatedAt()),
                () -> assertNotNull(persisted.getVersion())
        );
    }

    @Test
    void declaresContentIdentityConstraintAndLookupIndexes() {
        Table table = AssetContent.class.getAnnotation(Table.class);

        assertNotNull(table);
        assertEquals("blob_helper_asset_content", table.name());

        UniqueConstraint identityConstraint = table.uniqueConstraints()[0];
        assertEquals("uk_blob_helper_asset_content_identity", identityConstraint.name());
        assertEquals(
                Arrays.asList("hash_algorithm", "content_hash", "size_bytes"),
                Arrays.asList(identityConstraint.columnNames())
        );

        Map<String, String> indexes = Arrays.stream(table.indexes())
                .collect(Collectors.toMap(Index::name, Index::columnList));
        assertEquals(Map.of(
                "idx_blob_helper_asset_content_hash", "content_hash",
                "idx_blob_helper_asset_content_object_key", "object_key",
                "idx_blob_helper_asset_content_ref_count", "ref_count"
        ), indexes);
    }

    @Test
    void rejectsInvalidRequiredMetadata() {
        String contentHash = "a".repeat(64);

        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> new AssetContent(
                        " ", contentHash, 1L, "object-key", "local", "bucket", null, null)),
                () -> assertThrows(IllegalArgumentException.class, () -> new AssetContent(
                        "SHA-256", " ", 1L, "object-key", "local", "bucket", null, null)),
                () -> assertThrows(IllegalArgumentException.class, () -> new AssetContent(
                        "SHA-256", contentHash, -1L, "object-key", "local", "bucket", null, null)),
                () -> assertThrows(IllegalArgumentException.class, () -> new AssetContent(
                        "SHA-256", contentHash, 1L, " ", "local", "bucket", null, null)),
                () -> assertThrows(IllegalArgumentException.class, () -> new AssetContent(
                        "SHA-256", contentHash, 1L, "object-key", " ", "bucket", null, null)),
                () -> assertThrows(IllegalArgumentException.class, () -> new AssetContent(
                        "SHA-256", contentHash, 1L, "object-key", "local", " ", null, null))
        );
    }
}
