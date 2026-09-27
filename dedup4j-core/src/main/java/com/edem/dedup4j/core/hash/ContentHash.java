package com.edem.dedup4j.core.hash;

/**
 * Represents a content hash.
 * @param algorithm
 * @param hash
 * @param sizeBytes
 */
public record ContentHash(String algorithm, String hash, long sizeBytes) {
}
