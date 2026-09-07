package com.edem.blobhelper.service;

import com.edem.blobhelper.core.hash.ContentHash;
import com.edem.blobhelper.core.model.BlobReference;
import com.edem.blobhelper.jpa.AssetContent;

final class BlobReferences {

    private BlobReferences() {
    }

    static BlobReference from(AssetContent content, boolean duplicate) {
        return new BlobReference(
                content.getId(),
                new ContentHash(
                        content.getHashAlgorithm(),
                        content.getContentHash(),
                        content.getSizeBytes()
                ),
                content.getContentType(),
                content.getStorageProvider(),
                content.getBucketOrContainer(),
                content.getObjectKey(),
                duplicate
        );
    }
}
