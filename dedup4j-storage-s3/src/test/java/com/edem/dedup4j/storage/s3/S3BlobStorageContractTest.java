package com.edem.dedup4j.storage.s3;

import com.edem.dedup4j.core.exception.BlobStorageException;
import com.edem.dedup4j.core.exception.ContentNotFoundException;
import com.edem.dedup4j.core.storage.BlobResource;
import com.edem.dedup4j.core.storage.PutBlobRequest;
import com.edem.dedup4j.core.storage.StoredBlob;
import org.junit.jupiter.api.Test;

import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class S3BlobStorageContractTest {

    @Test
    void putGetDeleteAndExistsRoundTrip() throws IOException {
        FakeS3 fake = new FakeS3();
        S3BlobStorage storage = new S3BlobStorage(fake.client(), properties());
        byte[] payload = "round-trip-bytes".getBytes(StandardCharsets.UTF_8);

        StoredBlob stored = storage.put(new PutBlobRequest(
                "sha256/ab/abcdef",
                new ByteArrayInputStream(payload),
                payload.length,
                "text/plain",
                "notes.txt",
                Map.of("origin", "test")
        ));

        assertEquals("sha256/ab/abcdef", stored.objectKey());
        assertEquals(S3BlobStorage.PROVIDER, stored.provider());
        assertEquals("blob-bucket", stored.bucketOrContainer());
        assertEquals(payload.length, stored.sizeBytes());
        assertEquals("text/plain", stored.contentType());
        assertEquals("sha256-checksum", stored.checksum());
        assertNotNull(stored.createdAt());
        assertEquals("blob-bucket", fake.putRequest.bucket());
        assertEquals("sha256/ab/abcdef", fake.putRequest.key());
        assertEquals("text/plain", fake.putRequest.contentType());
        assertEquals("notes.txt", fake.putRequest.contentDisposition());
        assertEquals(Map.of("origin", "test"), fake.putRequest.metadata());

        assertTrue(storage.exists("sha256/ab/abcdef"));
        try (BlobResource resource = storage.get("sha256/ab/abcdef")) {
            assertEquals("sha256/ab/abcdef", resource.objectKey());
            assertEquals(payload.length, resource.sizeBytes());
            assertEquals("text/plain", resource.contentType());
            assertEquals(Map.of("origin", "test"), resource.metadata());
            assertArrayEquals(payload, readAll(resource.content()));
        }

        storage.delete("sha256/ab/abcdef");
        storage.delete("sha256/ab/abcdef");

        assertFalse(storage.exists("sha256/ab/abcdef"));
        assertThrows(ContentNotFoundException.class, () -> storage.get("sha256/ab/abcdef"));
    }

    @Test
    void missingObjectIsNotFoundForGetAndFalseForExists() {
        FakeS3 fake = new FakeS3();
        S3BlobStorage storage = new S3BlobStorage(fake.client(), properties());

        assertFalse(storage.exists("missing/object"));
        assertThrows(ContentNotFoundException.class, () -> storage.get("missing/object"));
    }

    @Test
    void providerFailureIsMappedToBlobStorageException() {
        FakeS3 fake = new FakeS3();
        fake.failure = S3Exception.builder().statusCode(503).message("service unavailable").build();
        S3BlobStorage storage = new S3BlobStorage(fake.client(), properties());

        BlobStorageException failure = assertThrows(BlobStorageException.class,
                () -> storage.put(request("failed/object", new byte[]{1, 2, 3})));

        assertTrue(failure.getMessage().contains("failed/object"));
        assertEquals(fake.failure, failure.getCause());
    }

    @Test
    void deleteOfMissingObjectRemainsIdempotentWhenProviderReturnsNotFound() {
        FakeS3 fake = new FakeS3();
        fake.deleteMissing = true;
        S3BlobStorage storage = new S3BlobStorage(fake.client(), properties());

        assertDoesNotThrow(() -> storage.delete("missing/object"));
    }

    private static S3BlobStorageProperties properties() {
        S3BlobStorageProperties properties = new S3BlobStorageProperties();
        properties.setBucket("blob-bucket");
        properties.setRegion("eu-west-1");
        return properties;
    }

    private static PutBlobRequest request(String objectKey, byte[] payload) {
        return new PutBlobRequest(
                objectKey,
                new ByteArrayInputStream(payload),
                payload.length,
                "application/octet-stream",
                null,
                null
        );
    }

    private static byte[] readAll(InputStream stream) {
        try (stream) {
            return stream.readAllBytes();
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static final class FakeS3 implements InvocationHandler {

        private final Map<String, byte[]> objects = new HashMap<>();
        private final Map<String, Map<String, String>> metadata = new HashMap<>();
        private PutObjectRequest putRequest;
        private AwsServiceException failure;
        private boolean deleteMissing;

        private S3Client client() {
            return (S3Client) Proxy.newProxyInstance(
                    S3Client.class.getClassLoader(),
                    new Class<?>[]{S3Client.class},
                    this
            );
        }

        @Override
        public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] arguments)
                throws Throwable {
            if (method.getName().equals("putObject")) {
                if (failure != null) {
                    throw failure;
                }
                putRequest = (PutObjectRequest) arguments[0];
                objects.put(putRequest.key(), "round-trip-bytes".getBytes(StandardCharsets.UTF_8));
                metadata.put(putRequest.key(), putRequest.metadata());
                return PutObjectResponse.builder().checksumSHA256("sha256-checksum").build();
            }
            if (method.getName().equals("getObject")) {
                String key = ((software.amazon.awssdk.services.s3.model.GetObjectRequest) arguments[0]).key();
                byte[] object = objects.get(key);
                if (object == null) {
                    throw notFound();
                }
                GetObjectResponse response = GetObjectResponse.builder()
                        .contentLength((long) object.length)
                        .contentType("text/plain")
                        .metadata(metadata.getOrDefault(key, Map.of()))
                        .checksumSHA256("sha256-checksum")
                        .build();
                return new ResponseInputStream<>(response, new ByteArrayInputStream(object));
            }
            if (method.getName().equals("headObject")) {
                String key = ((software.amazon.awssdk.services.s3.model.HeadObjectRequest) arguments[0]).key();
                byte[] object = objects.get(key);
                if (object == null) {
                    throw notFound();
                }
                return HeadObjectResponse.builder().contentLength((long) object.length).build();
            }
            if (method.getName().equals("deleteObject")) {
                if (deleteMissing) {
                    throw notFound();
                }
                String key = ((software.amazon.awssdk.services.s3.model.DeleteObjectRequest) arguments[0]).key();
                objects.remove(key);
                metadata.remove(key);
                return null;
            }
            if (method.getName().equals("close")) {
                return null;
            }
            if (method.getName().equals("toString")) {
                return "FakeS3";
            }
            if (method.getName().equals("hashCode")) {
                return System.identityHashCode(proxy);
            }
            if (method.getName().equals("equals")) {
                return proxy == arguments[0];
            }
            return defaultValue(method.getReturnType());
        }

        private AwsServiceException notFound() {
            return S3Exception.builder().statusCode(404).message("not found").build();
        }

        private static Object defaultValue(Class<?> type) {
            if (!type.isPrimitive()) {
                return null;
            }
            if (type == boolean.class) {
                return false;
            }
            if (type == char.class) {
                return '\0';
            }
            if (type == byte.class) {
                return (byte) 0;
            }
            if (type == short.class) {
                return (short) 0;
            }
            if (type == int.class) {
                return 0;
            }
            if (type == long.class) {
                return 0L;
            }
            if (type == float.class) {
                return 0F;
            }
            return 0D;
        }
    }
}
