package com.edem.dedup4j.core.storage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BlobStorageApiTest {

    @Test
    void exposesProviderNeutralOperations() throws Exception {
        assertEquals(
                StoredBlob.class,
                BlobStorage.class.getMethod("put", PutBlobRequest.class).getReturnType()
        );
        assertEquals(
                BlobResource.class,
                BlobStorage.class.getMethod("get", String.class).getReturnType()
        );
        assertEquals(
                void.class,
                BlobStorage.class.getMethod("delete", String.class).getReturnType()
        );
        assertEquals(
                boolean.class,
                BlobStorage.class.getMethod("exists", String.class).getReturnType()
        );
    }
}
