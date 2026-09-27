package com.edem.dedup4j.service;

import com.edem.dedup4j.core.model.BlobReference;
import com.edem.dedup4j.core.model.BlobLocation;
import com.edem.dedup4j.core.model.StoreBlobCommand;
import com.edem.dedup4j.core.storage.BlobResource;

import java.util.UUID;

/**
 * Application-facing operations for deduplicated physical blob content.
 *
 * <p>The service owns physical content references only. Consuming applications
 * remain responsible for their logical asset records and HTTP/API models.</p>
 */
public interface BlobDeduplicationService {

    BlobReference store(StoreBlobCommand command);

    void retain(UUID assetContentId);

    void release(UUID assetContentId);

    BlobResource get(UUID assetContentId);

    BlobLocation location(UUID assetContentId);
}
