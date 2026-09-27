package com.edem.dedup4j.storage.local;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class LocalBlobStoragePropertiesTest {

    @Test
    void hasDefaultRootDirectory() {
        assertNotNull(new LocalBlobStorageProperties().getRootDirectory());
        assertEquals(Path.of("dedup4j-storage"), new LocalBlobStorageProperties().getRootDirectory());
    }

    @Test
    void acceptsCustomRootDirectory() {
        LocalBlobStorageProperties properties = new LocalBlobStorageProperties();

        properties.setRootDirectory(Path.of("/tmp/blob-store"));

        assertEquals(Path.of("/tmp/blob-store"), properties.getRootDirectory());
    }

    @Test
    void rejectsNullRootDirectory() {
        assertThrows(NullPointerException.class,
                () -> new LocalBlobStorageProperties().setRootDirectory(null),
                "rootDirectory must not be null");
    }
}
