package com.edem.dedup4j.autoconfigure;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.util.unit.DataSize;
import com.edem.dedup4j.autoconfigure.persistence.SchemaInitialization;
import com.edem.dedup4j.reconcile.ReconciliationMode;

import java.util.List;
import java.util.Map;
import java.net.URI;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Dedup4jPropertiesTest {

    @Test
    void bindsStorageAndDeduplicationProperties() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(Map.of(
                "dedup4j.storage.provider", "s3",
                "dedup4j.storage.key-prefix", "uploads",
                "dedup4j.deduplication.max-upload-size", "64MB"
        ));

        Dedup4jProperties properties = new Binder(source)
                .bind("dedup4j", Bindable.of(Dedup4jProperties.class))
                .orElseThrow(AssertionError::new);

        assertEquals("s3", properties.getStorage().getProvider());
        assertEquals("uploads", properties.getStorage().getKeyPrefix());
        assertEquals(DataSize.ofMegabytes(64), properties.getDeduplication().getMaxUploadSize());
    }

    @Test
    void bindsAllowedContentTypesFromCommaSeparatedValue() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(Map.of(
                "dedup4j.deduplication.allowed-content-types", "image/*, application/pdf"
        ));

        Dedup4jProperties properties = new Binder(source)
                .bind("dedup4j", Bindable.of(Dedup4jProperties.class))
                .orElseThrow(AssertionError::new);

        assertEquals(List.of("image/*", "application/pdf"),
                properties.getDeduplication().getAllowedContentTypes());
    }

    @Test
    void defaultsAllowedContentTypesToEmpty() {
        assertEquals(List.of(), new Dedup4jProperties().getDeduplication().getAllowedContentTypes());
    }

    @Test
    void defaultsReconciliationToDisabledNightlyReport() {
        Dedup4jProperties.Reconciliation reconciliation = new Dedup4jProperties().getReconciliation();

        assertFalse(reconciliation.isEnabled());
        assertEquals("0 0 3 * * *", reconciliation.getCron());
        assertNull(reconciliation.getZone());
        assertEquals(ReconciliationMode.REPORT, reconciliation.getMode());
        assertEquals(100, reconciliation.getSampleSize());
    }

    @Test
    void bindsReconciliationProperties() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(Map.of(
                "dedup4j.reconciliation.enabled", "true",
                "dedup4j.reconciliation.cron", "0 30 2 * * SUN",
                "dedup4j.reconciliation.zone", "Africa/Accra",
                "dedup4j.reconciliation.mode", "report",
                "dedup4j.reconciliation.sample-size", "25"
        ));

        Dedup4jProperties.Reconciliation reconciliation = new Binder(source)
                .bind("dedup4j", Bindable.of(Dedup4jProperties.class))
                .orElseThrow(AssertionError::new)
                .getReconciliation();

        assertTrue(reconciliation.isEnabled());
        assertEquals("0 30 2 * * SUN", reconciliation.getCron());
        assertEquals("Africa/Accra", reconciliation.getZone());
        assertEquals(ReconciliationMode.REPORT, reconciliation.getMode());
        assertEquals(25, reconciliation.getSampleSize());
    }

    @Test
    void defaultsMaxUploadSizeTo25Megabytes() {
        assertEquals(DataSize.ofMegabytes(25), new Dedup4jProperties().getDeduplication().getMaxUploadSize());
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
                new Dedup4jProperties().getPersistence().getInitializeSchema());
    }

    private SchemaInitialization bindSchemaMode(String value) {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(Map.of(
                "dedup4j.persistence.initialize-schema", value));
        return new Binder(source)
                .bind("dedup4j", Bindable.of(Dedup4jProperties.class))
                .orElseThrow(AssertionError::new)
                .getPersistence()
                .getInitializeSchema();
    }

    @Test
    void bindsAllProviderPropertiesUnderStorage() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(Map.of(
                "dedup4j.storage.provider", "s3",
                "dedup4j.storage.local.root-directory", "build/blobs",
                "dedup4j.storage.s3.bucket", "media",
                "dedup4j.storage.s3.region", "eu-west-1",
                "dedup4j.storage.s3.endpoint", "http://localhost:9000",
                "dedup4j.storage.s3.path-style", "true",
                "dedup4j.storage.azure.container", "media",
                "dedup4j.storage.azure.connection-string", "UseDevelopmentStorage=true",
                "dedup4j.storage.azure.endpoint", "http://127.0.0.1:10000/devstoreaccount1",
                "dedup4j.storage.azure.account-name", "devstoreaccount1"
        ));

        Dedup4jProperties properties = new Binder(source)
                .bind("dedup4j", Bindable.of(Dedup4jProperties.class))
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
