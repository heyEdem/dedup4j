package com.edem.dedup4j.service;

import com.edem.dedup4j.core.hash.ContentHash;
import com.edem.dedup4j.core.model.BlobReference;
import com.edem.dedup4j.jpa.AssetContent;

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
