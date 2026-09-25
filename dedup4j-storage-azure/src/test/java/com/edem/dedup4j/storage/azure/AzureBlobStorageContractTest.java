package com.edem.dedup4j.storage.azure;

import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.common.policy.RequestRetryOptions;
import com.azure.storage.common.policy.RetryPolicyType;
import com.edem.dedup4j.core.exception.BlobStorageException;
import com.edem.dedup4j.core.exception.ContentNotFoundException;
import com.edem.dedup4j.core.storage.BlobResource;
import com.edem.dedup4j.core.storage.PutBlobRequest;
import com.edem.dedup4j.core.storage.StoredBlob;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AzureBlobStorageContractTest {

    private static final String CONTAINER = "blob-container";
    private static final String OBJECT_KEY = "sha256/ab/abcdef";

    private FakeAzure fake;

    @AfterEach
    void stopFakeServer() {
        if (fake != null) {
            fake.close();
        }
    }

    @Test
    void putGetDeleteAndExistsRoundTrip() throws IOException {
        fake = new FakeAzure();
        AzureBlobStorage storage = new AzureBlobStorage(client(fake), properties());
        byte[] payload = "round-trip-bytes".getBytes(StandardCharsets.UTF_8);

        StoredBlob stored = storage.put(new PutBlobRequest(
                OBJECT_KEY,
                new java.io.ByteArrayInputStream(payload),
                payload.length,
                "text/plain",
                "notes.txt",
                Map.of("origin", "test")
        ));

        assertEquals(OBJECT_KEY, stored.objectKey());
        assertEquals(AzureBlobStorage.PROVIDER, stored.provider());
        assertEquals(CONTAINER, stored.bucketOrContainer());
        assertEquals(payload.length, stored.sizeBytes());
        assertEquals("text/plain", stored.contentType());
        assertNotNull(stored.createdAt());
        assertArrayEquals(payload, fake.objects.get(OBJECT_KEY).content);
        assertEquals("text/plain", fake.objects.get(OBJECT_KEY).contentType);
        assertEquals("notes.txt", fake.objects.get(OBJECT_KEY).contentDisposition);
        assertEquals(Map.of("origin", "test"), fake.objects.get(OBJECT_KEY).metadata);

        assertTrue(storage.exists(OBJECT_KEY));
        try (BlobResource resource = storage.get(OBJECT_KEY)) {
            assertEquals(OBJECT_KEY, resource.objectKey());
            assertEquals(payload.length, resource.sizeBytes());
            assertEquals("text/plain", resource.contentType());
            assertArrayEquals(payload, resource.content().readAllBytes());
        }

        storage.delete(OBJECT_KEY);
        storage.delete(OBJECT_KEY);

        assertFalse(storage.exists(OBJECT_KEY));
        assertThrows(ContentNotFoundException.class, () -> storage.get(OBJECT_KEY));
    }

    @Test
    void missingObjectIsNotFoundForGetAndFalseForExists() {
        fake = new FakeAzure();
        AzureBlobStorage storage = new AzureBlobStorage(client(fake), properties());

        assertFalse(storage.exists(OBJECT_KEY));
        assertThrows(ContentNotFoundException.class, () -> storage.get(OBJECT_KEY));
    }

    @Test
    void providerFailureIsMappedToBlobStorageException() {
        fake = new FakeAzure();
        fake.failureStatus = 503;
        AzureBlobStorage storage = new AzureBlobStorage(client(fake), properties());

        BlobStorageException failure = assertThrows(BlobStorageException.class,
                () -> storage.put(request(OBJECT_KEY, new byte[]{1, 2, 3})));

        assertTrue(failure.getMessage().contains(OBJECT_KEY));
        assertTrue(failure.getCause() instanceof com.azure.storage.blob.models.BlobStorageException);
    }

    @Test
    void deleteOfMissingObjectIsIdempotent() {
        fake = new FakeAzure();
        AzureBlobStorage storage = new AzureBlobStorage(client(fake), properties());

        assertDoesNotThrow(() -> storage.delete(OBJECT_KEY));
    }

    private BlobContainerClient client(FakeAzure server) {
        return new BlobServiceClientBuilder()
                .endpoint(server.endpoint().toString())
                .retryOptions(new RequestRetryOptions(
                        RetryPolicyType.FIXED,
                        1,
                        Duration.ofSeconds(1),
                        Duration.ofMillis(1),
                        Duration.ofMillis(1),
                        null))
                .buildClient()
                .getBlobContainerClient(CONTAINER);
    }

    private static AzureBlobStorageProperties properties() {
        AzureBlobStorageProperties properties = new AzureBlobStorageProperties();
        properties.setContainer(CONTAINER);
        return properties;
    }

    private static PutBlobRequest request(String objectKey, byte[] payload) {
        return new PutBlobRequest(
                objectKey,
                new java.io.ByteArrayInputStream(payload),
                payload.length,
                "application/octet-stream",
                null,
                null
        );
    }

    private static final class FakeAzure implements HttpHandler, AutoCloseable {

        private final HttpServer server;
        private final Map<String, StoredObject> objects = new HashMap<>();
        private int failureStatus;

        private FakeAzure() {
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                server.createContext("/", this);
                server.start();
            } catch (IOException failure) {
                throw new IllegalStateException("Could not start fake Azure server", failure);
            }
        }

        private URI endpoint() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                if (failureStatus != 0) {
                    respond(exchange, failureStatus, new byte[0]);
                    return;
                }

                String key = key(exchange);
                switch (exchange.getRequestMethod()) {
                    case "PUT" -> put(exchange, key);
                    case "HEAD" -> head(exchange, key);
                    case "GET" -> get(exchange, key);
                    case "DELETE" -> delete(exchange, key);
                    default -> respond(exchange, 405, new byte[0]);
                }
            } finally {
                exchange.close();
            }
        }

        private void put(HttpExchange exchange, String key) throws IOException {
            byte[] content = exchange.getRequestBody().readAllBytes();
            Map<String, String> metadata = new HashMap<>();
            exchange.getRequestHeaders().forEach((name, values) -> {
                if (name.toLowerCase().startsWith("x-ms-meta-") && !values.isEmpty()) {
                    metadata.put(name.substring("x-ms-meta-".length()).toLowerCase(), values.get(0));
                }
            });
            StoredObject object = new StoredObject(
                    content,
                    exchange.getRequestHeaders().getFirst("x-ms-blob-content-type"),
                    exchange.getRequestHeaders().getFirst("x-ms-blob-content-disposition"),
                    metadata
            );
            objects.put(key, object);
            exchange.getResponseHeaders().set("ETag", "\"fake-etag\"");
            respond(exchange, 201, new byte[0]);
        }

        private void head(HttpExchange exchange, String key) throws IOException {
            StoredObject object = objects.get(key);
            if (object == null) {
                respond(exchange, 404, new byte[0]);
                return;
            }
            addObjectHeaders(exchange, object);
            exchange.sendResponseHeaders(200, -1);
        }

        private void get(HttpExchange exchange, String key) throws IOException {
            StoredObject object = objects.get(key);
            if (object == null) {
                respond(exchange, 404, new byte[0]);
                return;
            }
            addObjectHeaders(exchange, object);
            byte[] body = object.content;
            String range = exchange.getRequestHeaders().getFirst("x-ms-range");
            if (range != null && range.startsWith("bytes=")) {
                String[] bounds = range.substring("bytes=".length()).split("-", 2);
                int start = Integer.parseInt(bounds[0]);
                int end = bounds.length == 1 || bounds[1].isBlank()
                        ? body.length - 1
                        : Integer.parseInt(bounds[1]);
                end = Math.min(end, body.length - 1);
                byte[] ranged = java.util.Arrays.copyOfRange(body, start, end + 1);
                exchange.getResponseHeaders().set(
                        "Content-Range", "bytes " + start + "-" + end + "/" + body.length);
                respond(exchange, 206, ranged);
                return;
            }
            respond(exchange, 200, body);
        }

        private void delete(HttpExchange exchange, String key) throws IOException {
            if (objects.remove(key) == null) {
                respond(exchange, 404, new byte[0]);
                return;
            }
            respond(exchange, 202, new byte[0]);
        }

        private void addObjectHeaders(HttpExchange exchange, StoredObject object) {
            exchange.getResponseHeaders().set("Content-Length", String.valueOf(object.content.length));
            exchange.getResponseHeaders().set("Content-Type", object.contentType);
            exchange.getResponseHeaders().set("Content-Disposition", object.contentDisposition);
            exchange.getResponseHeaders().set("ETag", "\"fake-etag\"");
            object.metadata.forEach((key, value) -> exchange.getResponseHeaders().set("x-ms-meta-" + key, value));
        }

        private String key(HttpExchange exchange) {
            String path = exchange.getRequestURI().getPath();
            String prefix = "/blob-container/";
            if (!path.startsWith(prefix)) {
                throw new IllegalArgumentException("Unexpected Azure path: " + path);
            }
            return URLDecoder.decode(path.substring(prefix.length()), StandardCharsets.UTF_8);
        }

        private void respond(HttpExchange exchange, int status, byte[] body) throws IOException {
            exchange.sendResponseHeaders(status, body.length);
            if (body.length > 0) {
                exchange.getResponseBody().write(body);
            }
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    private static final class StoredObject {

        private final byte[] content;
        private final String contentType;
        private final String contentDisposition;
        private final Map<String, String> metadata;

        private StoredObject(
                byte[] content,
                String contentType,
                String contentDisposition,
                Map<String, String> metadata
        ) {
            this.content = content;
            this.contentType = contentType;
            this.contentDisposition = contentDisposition;
            this.metadata = Map.copyOf(metadata);
        }
    }
}
