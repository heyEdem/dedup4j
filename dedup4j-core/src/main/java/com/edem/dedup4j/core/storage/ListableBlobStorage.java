package com.edem.dedup4j.core.storage;

import java.util.stream.Stream;

/** Storage that can list its objects, which the orphaned object sweep needs. */
public interface ListableBlobStorage extends BlobStorage {

    /**
     * Lists the objects whose keys start with {@code keyPrefix}, fetching further pages lazily.
     * Close the stream when done. A page that fails to load may throw any runtime exception while the
     * stream is read.
     */
    Stream<ListedBlob> list(String keyPrefix);
}
