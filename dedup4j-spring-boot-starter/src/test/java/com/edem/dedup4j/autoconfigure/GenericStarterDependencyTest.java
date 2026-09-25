package com.edem.dedup4j.autoconfigure;

import com.edem.dedup4j.storage.azure.AzureBlobStorage;
import com.edem.dedup4j.storage.local.LocalBlobStorage;
import com.edem.dedup4j.storage.s3.S3BlobStorage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GenericStarterDependencyTest {

    @Test
    void includesAllProviderAdapters() {
        assertNotNull(LocalBlobStorage.class);
        assertNotNull(S3BlobStorage.class);
        assertNotNull(AzureBlobStorage.class);
    }

    @Test
    void excludesObservabilityModules() {
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("com.edem.dedup4j.management.Dedup4jManagementAutoConfiguration"));
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("com.edem.blobhelper.dashboard.autoconfigure.BlobHelperDashboardAutoConfiguration"));
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("com.edem.blobhelper.dashboard.BlobHelperDashboardApplication"));
    }
}
