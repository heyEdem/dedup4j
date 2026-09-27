package com.edem.dedup4j.storage.azure;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AzureBlobStoragePropertiesTest {

    @Test
    void storesAzureConnectionSettings() {
        AzureBlobStorageProperties properties = new AzureBlobStorageProperties();
        URI endpoint = URI.create("https://account.blob.core.windows.net");

        properties.setContainer("blob-container");
        properties.setConnectionString("UseDevelopmentStorage=true");
        properties.setEndpoint(endpoint);
        properties.setAccountName("account");

        assertEquals("blob-container", properties.getContainer());
        assertEquals("UseDevelopmentStorage=true", properties.getConnectionString());
        assertEquals(endpoint, properties.getEndpoint());
        assertEquals("account", properties.getAccountName());
    }
}
