package com.edem.dedup4j.storage.azure;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.blob.models.BlobErrorCode;
import com.azure.storage.blob.models.BlobHttpHeaders;
import com.azure.storage.blob.models.BlobProperties;
import com.azure.storage.blob.models.BlobRequestConditions;
import com.azure.storage.blob.models.ListBlobsOptions;
import com.azure.storage.blob.options.BlobParallelUploadOptions;
import com.azure.core.util.Context;
import com.edem.dedup4j.core.exception.BlobStorageException;
import com.edem.dedup4j.core.exception.BlobValidationException;
import com.edem.dedup4j.core.exception.ContentNotFoundException;
import com.edem.dedup4j.core.storage.BlobResource;
import com.edem.dedup4j.core.storage.ListableBlobStorage;
import com.edem.dedup4j.core.storage.ListedBlob;
import com.edem.dedup4j.core.storage.PutBlobRequest;
import com.edem.dedup4j.core.storage.StoredBlob;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.Objects;
import java.util.stream.Stream;

public class AzureBlobStorage implements ListableBlobStorage {

    public static final String PROVIDER = "azure";

    private final BlobContainerClient client;
    private final AzureBlobStorageProperties properties;

    public AzureBlobStorage(AzureBlobStorageProperties properties) {
        this(createClient(properties), properties);
    }

    public AzureBlobStorage(BlobContainerClient client, AzureBlobStorageProperties properties) {
        if (client == null) {
            throw new BlobValidationException("client must not be null");
        }
        if (properties == null) {
            throw new BlobValidationException("properties must not be null");
        }
        this.client = client;
        this.properties = properties;
    }

    @Override
    public StoredBlob put(PutBlobRequest request) {
        if (request == null) {
            throw new BlobValidationException("request must not be null");
        }
        request.requireAsciiMetadata();
        String container = container();
        try (InputStream content = request.content()) {
            BlobHttpHeaders headers = new BlobHttpHeaders();
            if (request.contentType() != null && !request.contentType().isBlank()) {
                headers.setContentType(request.contentType());
            }
            if (request.contentDisposition() != null) {
                headers.setContentDisposition(request.contentDisposition());
            }

            BlobParallelUploadOptions options = new BlobParallelUploadOptions(content)
                    .setHeaders(headers)
                    .setMetadata(request.metadata());
            client.getBlobClient(request.objectKey()).uploadWithResponse(options, null, Context.NONE);
            return new StoredBlob(
                    request.objectKey(),
                    PROVIDER,
                    container,
                    request.sizeBytes(),
                    request.contentType(),
                    null,
                    Instant.now()
            );
        } catch (IOException | com.azure.storage.blob.models.BlobStorageException failure) {
            throw new BlobStorageException("Failed to store object: " + request.objectKey(), failure);
        }
    }

    @Override
    public BlobResource get(String objectKey) {
        validateKey(objectKey);
        BlobClient blob = client.getBlobClient(objectKey);
        try {
            BlobProperties properties = blob.getProperties();
            InputStream content = blob.openInputStream();
            try {
                return new BlobResource(
                        objectKey,
                        content,
                        properties.getBlobSize(),
                        properties.getContentType(),
                        properties.getMetadata()
                );
            } catch (RuntimeException failure) {
                closeAfterFailure(content, failure);
                throw failure;
            }
        } catch (com.azure.storage.blob.models.BlobStorageException failure) {
            if (isNotFound(failure)) {
                throw new ContentNotFoundException("Azure blob not found: " + objectKey, failure);
            }
            throw new BlobStorageException("Failed to read object: " + objectKey, failure);
        }
    }

    /**
     * Deletes only the write read just before the delete, by sending its ETag as {@code If-Match}.
     * Azure changes the ETag on every write, even of identical bytes, so a delete the service
     * applies after this call gave up cannot remove an object rewritten in the meantime. A changed
     * ETag fails the call with the object kept; the ETag is not refreshed and retried.
     */
    @Override
    public void delete(String objectKey) {
        validateKey(objectKey);
        BlobClient blob = client.getBlobClient(objectKey);
        try {
            String eTag = blob.getProperties().getETag();
            blob.deleteIfExistsWithResponse(
                    null, new BlobRequestConditions().setIfMatch(eTag), null, Context.NONE
            );
        } catch (com.azure.storage.blob.models.BlobStorageException failure) {
            if (isNotFound(failure)) {
                return;
            }
            if (BlobErrorCode.CONDITION_NOT_MET.equals(failure.getErrorCode())) {
                throw new BlobStorageException("Object was rewritten before its delete; kept: " + objectKey, failure);
            }
            throw new BlobStorageException("Failed to delete object: " + objectKey, failure);
        }
    }

    @Override
    public boolean exists(String objectKey) {
        validateKey(objectKey);
        try {
            return Boolean.TRUE.equals(client.getBlobClient(objectKey).exists());
        } catch (com.azure.storage.blob.models.BlobStorageException failure) {
            if (isNotFound(failure)) {
                return false;
            }
            throw new BlobStorageException("Failed to check object: " + objectKey, failure);
        }
    }

    /** Pages through the container listing lazily; a later page that fails throws the SDK exception while the stream is read. */
    @Override
    public Stream<ListedBlob> list(String keyPrefix) {
        Objects.requireNonNull(keyPrefix, "keyPrefix must not be null");
        String container = container();
        try {
            return client.listBlobs(new ListBlobsOptions().setPrefix(keyPrefix), null)
                    .stream()
                    .map(item -> new ListedBlob(item.getName(), PROVIDER, container,
                            item.getProperties().getContentLength(),
                            item.getProperties().getLastModified().toInstant()));
        } catch (com.azure.storage.blob.models.BlobStorageException failure) {
            throw new BlobStorageException("Failed to list objects under: " + keyPrefix, failure);
        }
    }

    private String container() {
        String container = properties.getContainer();
        if (container == null || container.isBlank()) {
            throw new BlobValidationException("container must not be blank");
        }
        return container;
    }

    private void validateKey(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            throw new BlobValidationException("objectKey must not be blank");
        }
    }

    private boolean isNotFound(com.azure.storage.blob.models.BlobStorageException failure) {
        return failure.getStatusCode() == 404;
    }

    private void closeAfterFailure(InputStream content, RuntimeException failure) {
        try {
            content.close();
        } catch (IOException closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    public static BlobContainerClient createClient(AzureBlobStorageProperties properties) {
        if (properties == null) {
            throw new BlobValidationException("properties must not be null");
        }
        String container = properties.getContainer();
        if (container == null || container.isBlank()) {
            throw new BlobValidationException("container must not be blank");
        }

        BlobServiceClientBuilder builder = new BlobServiceClientBuilder();
        String connectionString = properties.getConnectionString();
        if (connectionString != null && !connectionString.isBlank()) {
            builder.connectionString(connectionString);
        } else if (properties.getEndpoint() != null) {
            builder.endpoint(properties.getEndpoint().toString());
        } else {
            throw new BlobValidationException("connectionString or endpoint must be configured");
        }
        if (properties.getEndpoint() != null) {
            builder.endpoint(properties.getEndpoint().toString());
        }
        return builder.buildClient().getBlobContainerClient(container);
    }
}
