package com.edem.blobhelper.core.model;

import com.edem.blobhelper.core.exception.BlobValidationException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BlobLocationTest {

    @Test
    void requiresCompleteStableIdentity() {
        BlobLocation location = new BlobLocation("s3", "images", "sha-256/ab/hash");

        assertEquals("s3", location.provider());
        assertEquals("images", location.bucketOrContainer());
        assertEquals("sha-256/ab/hash", location.objectKey());
        assertEquals(location, new BlobLocation("s3", "images", "sha-256/ab/hash"));
    }

    @Test
    void rejectsBlankOrNullFields() {
        assertThrows(BlobValidationException.class,
                () -> new BlobLocation(null, "bucket", "key"));
        assertThrows(BlobValidationException.class,
                () -> new BlobLocation(" ", "bucket", "key"));
        assertThrows(BlobValidationException.class,
                () -> new BlobLocation("s3", null, "key"));
        assertThrows(BlobValidationException.class,
                () -> new BlobLocation("s3", " ", "key"));
        assertThrows(BlobValidationException.class,
                () -> new BlobLocation("s3", "bucket", null));
        assertThrows(BlobValidationException.class,
                () -> new BlobLocation("s3", "bucket", " "));
    }
}
