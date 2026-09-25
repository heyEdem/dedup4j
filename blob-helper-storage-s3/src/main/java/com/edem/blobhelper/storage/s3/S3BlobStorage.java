package com.edem.blobhelper.storage.s3;

import com.edem.dedup4j.core.exception.BlobStorageException;
import com.edem.dedup4j.core.exception.BlobValidationException;
import com.edem.dedup4j.core.exception.ContentNotFoundException;
import com.edem.dedup4j.core.storage.BlobResource;
import com.edem.dedup4j.core.storage.BlobStorage;
import com.edem.dedup4j.core.storage.PutBlobRequest;
import com.edem.dedup4j.core.storage.StoredBlob;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest.Builder;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;

public class S3BlobStorage implements BlobStorage, AutoCloseable {

    public static final String PROVIDER = "s3";

    private final S3Client client;
    private final S3BlobStorageProperties properties;

    public S3BlobStorage(S3BlobStorageProperties properties) {
        this(createClient(properties), properties);
    }

    public S3BlobStorage(S3Client client, S3BlobStorageProperties properties) {
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
        String bucket = bucket();
        try (InputStream content = request.content()) {
            software.amazon.awssdk.services.s3.model.PutObjectRequest objectRequest = putRequest(bucket, request);
            var response = client.putObject(
                    objectRequest,
                    RequestBody.fromInputStream(content, request.sizeBytes())
            );
            return new StoredBlob(
                    request.objectKey(),
                    PROVIDER,
                    bucket,
                    request.sizeBytes(),
                    request.contentType(),
                    response == null ? null : response.checksumSHA256(),
                    Instant.now()
            );
        } catch (IOException | SdkException failure) {
            throw new BlobStorageException("Failed to store object: " + request.objectKey(), failure);
        }
    }

    @Override
    public BlobResource get(String objectKey) {
        validateKey(objectKey);
        try {
            ResponseInputStream<GetObjectResponse> response = client.getObject(GetObjectRequest.builder()
                    .bucket(bucket())
                    .key(objectKey)
                    .build());
            GetObjectResponse metadata = response.response();
            return new BlobResource(
                    objectKey,
                    response,
                    requireContentLength(metadata, objectKey),
                    metadata.contentType(),
                    metadata.metadata()
            );
        } catch (SdkException failure) {
            if (isNotFound(failure)) {
                throw new ContentNotFoundException("S3 object not found: " + objectKey, failure);
            }
            throw new BlobStorageException("Failed to read object: " + objectKey, failure);
        }
    }

    @Override
    public void delete(String objectKey) {
        validateKey(objectKey);
        try {
            client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(bucket())
                    .key(objectKey)
                    .build());
        } catch (SdkException failure) {
            if (isNotFound(failure)) {
                return;
            }
            throw new BlobStorageException("Failed to delete object: " + objectKey, failure);
        }
    }

    @Override
    public boolean exists(String objectKey) {
        validateKey(objectKey);
        try {
            client.headObject(HeadObjectRequest.builder()
                    .bucket(bucket())
                    .key(objectKey)
                    .build());
            return true;
        } catch (SdkException failure) {
            if (isNotFound(failure)) {
                return false;
            }
            throw new BlobStorageException("Failed to check object: " + objectKey, failure);
        }
    }

    @Override
    public void close() {
        client.close();
    }

    private software.amazon.awssdk.services.s3.model.PutObjectRequest putRequest(
            String bucket,
            PutBlobRequest request
    ) {
        Builder builder = software.amazon.awssdk.services.s3.model.PutObjectRequest.builder()
                .bucket(bucket)
                .key(request.objectKey())
                .metadata(request.metadata());
        if (request.contentType() != null && !request.contentType().isBlank()) {
            builder.contentType(request.contentType());
        }
        if (request.originalFilename() != null && !request.originalFilename().isBlank()) {
            builder.contentDisposition(request.originalFilename());
        }
        return builder.build();
    }

    private String bucket() {
        String bucket = properties.getBucket();
        if (bucket == null || bucket.isBlank()) {
            throw new BlobValidationException("bucket must not be blank");
        }
        return bucket;
    }

    private void validateKey(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            throw new BlobValidationException("objectKey must not be blank");
        }
    }

    private long requireContentLength(GetObjectResponse response, String objectKey) {
        if (response == null || response.contentLength() == null) {
            throw new BlobStorageException("S3 response did not include content length: " + objectKey);
        }
        return response.contentLength();
    }

    private boolean isNotFound(SdkException failure) {
        return failure instanceof AwsServiceException serviceException
                && serviceException.statusCode() == 404;
    }

    private static S3Client createClient(S3BlobStorageProperties properties) {
        if (properties == null) {
            throw new BlobValidationException("properties must not be null");
        }
        S3ClientBuilder builder = S3Client.builder()
                .forcePathStyle(properties.isPathStyleAccess());
        if (properties.getRegion() != null && !properties.getRegion().isBlank()) {
            builder.region(Region.of(properties.getRegion()));
        }
        if (properties.getEndpointOverride() != null) {
            builder.endpointOverride(properties.getEndpointOverride());
        }
        return builder.build();
    }
}
