package com.edem.blobhelper.autoconfigure;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.util.unit.DataSize;
import com.edem.blobhelper.autoconfigure.persistence.SchemaInitialization;

import java.util.Map;
import java.net.URI;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlobHelperPropertiesTest {

    @Test
    void bindsStorageDeduplicationAndCleanupProperties() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(Map.of(
                "blob-helper.storage.provider", "s3",
                "blob-helper.storage.key-prefix", "uploads",
                "blob-helper.deduplication.hash-algorithm", "SHA-256",
                "blob-helper.deduplication.max-upload-size", "64MB",
                "blob-helper.deduplication.strict-content-type-validation", "true",
                "blob-helper.cleanup.delete-physical-on-zero-references", "false",
                "blob-helper.cleanup.reconciliation-enabled", "true"
        ));

        BlobHelperProperties properties = new Binder(source)
                .bind("blob-helper", Bindable.of(BlobHelperProperties.class))
                .orElseThrow(AssertionError::new);

        assertEquals("s3", properties.getStorage().getProvider());
        assertEquals("uploads", properties.getStorage().getKeyPrefix());
        assertEquals("SHA-256", properties.getDeduplication().getHashAlgorithm());
        assertEquals(DataSize.ofMegabytes(64), properties.getDeduplication().getMaxUploadSize());
        assertTrue(properties.getDeduplication().isStrictContentTypeValidation());
        assertFalse(properties.getCleanup().isDeletePhysicalOnZeroReferences());
        assertTrue(properties.getCleanup().isReconciliationEnabled());
    }

    @Test
    void disablesReconciliationByDefault() {
        BlobHelperProperties properties = new BlobHelperProperties();

        assertFalse(properties.getCleanup().isReconciliationEnabled());
    }

    @Test
    void bindsSchemaInitializationModesWithRelaxedNames() {
        assertEquals(SchemaInitialization.ALWAYS, bindSchemaMode("always"));
        assertEquals(SchemaInitialization.NEVER, bindSchemaMode("never"));
        assertEquals(SchemaInitialization.EMBEDDED, bindSchemaMode("embedded"));
    }

    @Test
    void initializesSchemaForEmbeddedDatabasesByDefault() {
        assertEquals(SchemaInitialization.EMBEDDED,
                new BlobHelperProperties().getPersistence().getInitializeSchema());
    }

    private SchemaInitialization bindSchemaMode(String value) {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(Map.of(
                "blob-helper.persistence.initialize-schema", value));
        return new Binder(source)
                .bind("blob-helper", Bindable.of(BlobHelperProperties.class))
                .orElseThrow(AssertionError::new)
                .getPersistence()
                .getInitializeSchema();
    }

    @Test
    void bindsAllProviderPropertiesUnderStorage() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(Map.of(
                "blob-helper.storage.provider", "s3",
                "blob-helper.storage.local.root-directory", "build/blobs",
                "blob-helper.storage.s3.bucket", "media",
                "blob-helper.storage.s3.region", "eu-west-1",
                "blob-helper.storage.s3.endpoint", "http://localhost:9000",
                "blob-helper.storage.s3.path-style", "true",
                "blob-helper.storage.azure.container", "media",
                "blob-helper.storage.azure.connection-string", "UseDevelopmentStorage=true",
                "blob-helper.storage.azure.endpoint", "http://127.0.0.1:10000/devstoreaccount1",
                "blob-helper.storage.azure.account-name", "devstoreaccount1"
        ));

        BlobHelperProperties properties = new Binder(source)
                .bind("blob-helper", Bindable.of(BlobHelperProperties.class))
                .orElseThrow(AssertionError::new);

        assertEquals("s3", properties.getStorage().getProvider());
        assertEquals(Path.of("build/blobs"), properties.getStorage().getLocal().getRootDirectory());
        assertEquals("media", properties.getStorage().getS3().getBucket());
        assertEquals("eu-west-1", properties.getStorage().getS3().getRegion());
        assertEquals(URI.create("http://localhost:9000"), properties.getStorage().getS3().getEndpoint());
        assertTrue(properties.getStorage().getS3().isPathStyle());
        assertEquals("media", properties.getStorage().getAzure().getContainer());
        assertEquals("UseDevelopmentStorage=true", properties.getStorage().getAzure().getConnectionString());
        assertEquals(URI.create("http://127.0.0.1:10000/devstoreaccount1"), properties.getStorage().getAzure().getEndpoint());
        assertEquals("devstoreaccount1", properties.getStorage().getAzure().getAccountName());
    }
}
