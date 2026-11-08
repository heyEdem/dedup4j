package com.edem.dedup4j.core.hash;

/**
 * A perceptual hash of content, such as an image's pHash. Two hashes are compared only when their algorithms are equal,
 * by that algorithm's own distance (usually the number of differing bits), never by numeric closeness of the values.
 * It never takes part in content identity, which is {@link ContentHash} alone.
 *
 * @param algorithm names the algorithm and every variant that changes its output, including the bit width, for example
 *                  {@code "dhash-64"}
 * @param hash      the hash, lowercase hex, zero-padded to the algorithm's full width
 */
public record SimilarityHash(String algorithm, String hash) {
}
