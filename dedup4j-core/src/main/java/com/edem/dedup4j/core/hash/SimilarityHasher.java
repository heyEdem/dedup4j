package com.edem.dedup4j.core.hash;

import java.io.IOException;
import java.io.InputStream;

/**
 * Computes a {@link SimilarityHash} that finds near-duplicates, such as the same image resized or recompressed.
 * Implementations live outside {@code dedup4j-core}, which stays free of image libraries. Exact-match deduplication
 * never uses this hash. The library does not call it yet.
 */
public interface SimilarityHasher {

    /**
     * Whether to try this hasher on content of the given type. Asked before the bytes are read, because the exact-match
     * hash consumes the upload stream. A supported type does not promise the bytes decode; {@link #hash} reports that.
     *
     * @param contentType the declared content type, possibly null; answer false for types this hasher cannot read
     */
    boolean supports(String contentType);

    /**
     * Hashes the content. The caller owns the stream and closes it.
     *
     * @throws IOException when the stream cannot be read or the bytes cannot be decoded, for example a corrupt image
     */
    SimilarityHash hash(InputStream inputStream) throws IOException;
}
