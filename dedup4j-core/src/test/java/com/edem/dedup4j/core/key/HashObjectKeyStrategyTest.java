package com.edem.dedup4j.core.key;

import com.edem.dedup4j.core.hash.ContentHash;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HashObjectKeyStrategyTest {

    @Test
    void generatesDeterministicKey() {
        ContentHash contentHash = new ContentHash(
                "SHA-256",
                "b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9",
                11
        );

        ObjectKeyStrategy strategy = new HashObjectKeyStrategy("uploads");

        assertEquals(
                "uploads/sha-256/b9/b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9",
                strategy.generateKey(contentHash)
        );
        assertEquals(
                "uploads/sha-256/b9/b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9",
                strategy.generateKey(contentHash)
        );
    }

    @Test
    void generatesRelativeKeyWithoutPrefix() {
        ContentHash contentHash = new ContentHash("SHA-256", "ab1234", 3);

        ObjectKeyStrategy strategy = new HashObjectKeyStrategy("");

        assertEquals("sha-256/ab/ab1234", strategy.generateKey(contentHash));
    }
}
