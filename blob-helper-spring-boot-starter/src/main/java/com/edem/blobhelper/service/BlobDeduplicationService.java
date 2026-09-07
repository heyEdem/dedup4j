package com.edem.blobhelper.service;

import com.edem.blobhelper.core.model.BlobReference;
import com.edem.blobhelper.core.model.BlobLocation;
import com.edem.blobhelper.core.model.StoreBlobCommand;
import com.edem.blobhelper.core.storage.BlobResource;

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
