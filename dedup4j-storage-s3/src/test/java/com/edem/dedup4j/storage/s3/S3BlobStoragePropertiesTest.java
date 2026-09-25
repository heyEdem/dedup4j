package com.edem.dedup4j.storage.s3;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class S3BlobStoragePropertiesTest {

    @Test
    void storesS3ConnectionSettings() {
        S3BlobStorageProperties properties = new S3BlobStorageProperties();
        URI endpoint = URI.create("http://localhost:9000");

        properties.setBucket("blob-bucket");
        properties.setRegion("eu-west-1");
        properties.setEndpointOverride(endpoint);
        properties.setPathStyleAccess(true);

        assertEquals("blob-bucket", properties.getBucket());
        assertEquals("eu-west-1", properties.getRegion());
        assertEquals(endpoint, properties.getEndpointOverride());
        assertTrue(properties.isPathStyleAccess());
    }

    @Test
    void disablesPathStyleAccessByDefault() {
        assertFalse(new S3BlobStorageProperties().isPathStyleAccess());
    }
}
