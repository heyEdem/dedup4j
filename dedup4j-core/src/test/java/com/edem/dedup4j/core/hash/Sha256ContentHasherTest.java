package com.edem.dedup4j.core.hash;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class Sha256ContentHasherTest {

    @Test
    void hashesExactBytes() throws Exception {
        byte[] bytes = "hello world".getBytes(StandardCharsets.UTF_8);

        ContentHash contentHash = new Sha256ContentHasher().hash(new ByteArrayInputStream(bytes));

        assertEquals("sha-256", contentHash.algorithm());
        assertEquals("b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9", contentHash.hash());
        assertEquals(bytes.length, contentHash.sizeBytes());
    }
}
