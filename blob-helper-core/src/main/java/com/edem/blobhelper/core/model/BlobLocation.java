package com.edem.blobhelper.core.model;

import com.edem.blobhelper.core.exception.BlobValidationException;

public record BlobLocation(
        String provider,
        String bucketOrContainer,
        String objectKey
) {
    public BlobLocation {
        provider = requireText(provider, "provider");
        bucketOrContainer = requireText(bucketOrContainer, "bucketOrContainer");
        objectKey = requireText(objectKey, "objectKey");
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new BlobValidationException(name + " must not be blank");
        }
        return value;
    }
}
