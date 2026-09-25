# Storage-Neutral SPI and Models Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add provider-neutral storage contracts, immutable command/result records, and domain exceptions to `dedup4j-core`.

**Architecture:** Keep all public types in the core module and express streams with `InputStream`, metadata with immutable `Map<String, String>` values, identity with the existing `ContentHash`, and storage failures with unchecked domain exceptions. `BlobResource` owns its stream and closes it through `AutoCloseable`.

**Tech Stack:** Java 21 records and interfaces, Maven, JUnit Jupiter 5.

---

### Task 1: Domain Exception Hierarchy

**Files:**
- Create: `dedup4j-core/src/test/java/com/edem/dedup4j/core/exception/DomainExceptionTest.java`
- Create: `dedup4j-core/src/main/java/com/edem/dedup4j/core/exception/Dedup4jException.java`
- Create: `dedup4j-core/src/main/java/com/edem/dedup4j/core/exception/BlobValidationException.java`
- Create: `dedup4j-core/src/main/java/com/edem/dedup4j/core/exception/BlobHashingException.java`
- Create: `dedup4j-core/src/main/java/com/edem/dedup4j/core/exception/BlobStorageException.java`
- Create: `dedup4j-core/src/main/java/com/edem/dedup4j/core/exception/ContentNotFoundException.java`
- Create: `dedup4j-core/src/main/java/com/edem/dedup4j/core/exception/ReferenceCountUnderflowException.java`

- [x] **Step 1: Write the failing hierarchy test**

```java
package com.edem.dedup4j.core.exception;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

class DomainExceptionTest {

    @Test
    void domainExceptionsShareBaseTypeAndRetainCause() {
        RuntimeException cause = new RuntimeException("provider failed");
        Dedup4jException exception = new BlobStorageException("put failed", cause);

        assertEquals("put failed", exception.getMessage());
        assertSame(cause, exception.getCause());
        assertInstanceOf(Dedup4jException.class, new BlobValidationException("invalid"));
        assertInstanceOf(Dedup4jException.class, new BlobHashingException("hash failed", cause));
        assertInstanceOf(Dedup4jException.class, new ContentNotFoundException("missing"));
        assertInstanceOf(Dedup4jException.class, new ReferenceCountUnderflowException("underflow"));
    }
}
```

- [x] **Step 2: Run the test and verify RED**

Run: `./mvnw -pl dedup4j-core -Dtest=DomainExceptionTest test`
Expected: compilation fails because the exception types do not exist.

- [x] **Step 3: Add the exception types**

```java
package com.edem.dedup4j.core.exception;

public class Dedup4jException extends RuntimeException {
    public Dedup4jException(String message) { super(message); }
    public Dedup4jException(String message, Throwable cause) { super(message, cause); }
}
```

```java
package com.edem.dedup4j.core.exception;

public final class BlobStorageException extends Dedup4jException {
    public BlobStorageException(String message) { super(message); }
    public BlobStorageException(String message, Throwable cause) { super(message, cause); }
}
```

```java
package com.edem.dedup4j.core.exception;

public final class BlobValidationException extends Dedup4jException {
    public BlobValidationException(String message) { super(message); }
    public BlobValidationException(String message, Throwable cause) { super(message, cause); }
}
```

```java
package com.edem.dedup4j.core.exception;

public final class BlobHashingException extends Dedup4jException {
    public BlobHashingException(String message) { super(message); }
    public BlobHashingException(String message, Throwable cause) { super(message, cause); }
}
```

```java
package com.edem.dedup4j.core.exception;

public final class ContentNotFoundException extends Dedup4jException {
    public ContentNotFoundException(String message) { super(message); }
    public ContentNotFoundException(String message, Throwable cause) { super(message, cause); }
}
```

```java
package com.edem.dedup4j.core.exception;

public final class ReferenceCountUnderflowException extends Dedup4jException {
    public ReferenceCountUnderflowException(String message) { super(message); }
    public ReferenceCountUnderflowException(String message, Throwable cause) { super(message, cause); }
}
```

- [x] **Step 4: Run the test and verify GREEN**

Run: `./mvnw -pl dedup4j-core -Dtest=DomainExceptionTest test`
Expected: one test passes with zero failures.

### Task 2: Storage Records and Resource Lifecycle

**Files:**
- Create: `dedup4j-core/src/test/java/com/edem/dedup4j/core/storage/StorageModelsTest.java`
- Create: `dedup4j-core/src/main/java/com/edem/dedup4j/core/storage/PutBlobRequest.java`
- Create: `dedup4j-core/src/main/java/com/edem/dedup4j/core/storage/StoredBlob.java`
- Create: `dedup4j-core/src/main/java/com/edem/dedup4j/core/storage/BlobResource.java`

- [x] **Step 1: Write failing storage-model tests**

```java
package com.edem.dedup4j.core.storage;

import com.edem.dedup4j.core.exception.BlobValidationException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StorageModelsTest {

    @Test
    void putRequestCopiesMetadata() {
        Map<String, String> metadata = new HashMap<>(Map.of("owner", "edem"));
        PutBlobRequest request = new PutBlobRequest("sha-256/ab/hash", new ByteArrayInputStream(new byte[0]), 0, "text/plain", "note.txt", metadata);
        metadata.put("owner", "changed");
        assertEquals("edem", request.metadata().get("owner"));
    }

    @Test
    void storedBlobCarriesProviderNeutralLocation() {
        Instant createdAt = Instant.parse("2026-07-15T00:00:00Z");
        StoredBlob blob = new StoredBlob("key", "s3", "bucket", 12, "text/plain", "checksum", createdAt);
        assertEquals("s3", blob.provider());
        assertEquals("bucket", blob.bucketOrContainer());
    }

    @Test
    void resourceClosesOwnedStream() throws Exception {
        TrackingInputStream stream = new TrackingInputStream();
        BlobResource resource = new BlobResource("key", stream, 0, null, null);
        resource.close();
        assertTrue(stream.closed);
        assertTrue(resource.metadata().isEmpty());
    }

    @Test
    void rejectsInvalidRequiredStorageFields() {
        assertThrows(BlobValidationException.class,
                () -> new PutBlobRequest(" ", InputStream.nullInputStream(), 0, null, null, Map.of()));
        assertThrows(BlobValidationException.class,
                () -> new StoredBlob("key", "s3", "bucket", -1, null, null, null));
    }

    private static final class TrackingInputStream extends ByteArrayInputStream {
        private boolean closed;
        private TrackingInputStream() { super(new byte[0]); }
        @Override public void close() throws IOException { closed = true; super.close(); }
    }
}
```

- [x] **Step 2: Run the test and verify RED**

Run: `./mvnw -pl dedup4j-core -Dtest=StorageModelsTest test`
Expected: compilation fails because the three storage records do not exist.

- [x] **Step 3: Implement the storage records**

```java
package com.edem.dedup4j.core.storage;

import com.edem.dedup4j.core.exception.BlobValidationException;
import java.io.InputStream;
import java.util.Map;

public record PutBlobRequest(String objectKey, InputStream content, long sizeBytes,
                             String contentType, String originalFilename,
                             Map<String, String> metadata) {
    public PutBlobRequest {
        if (objectKey == null || objectKey.isBlank()) throw new BlobValidationException("objectKey must not be blank");
        if (content == null) throw new BlobValidationException("content must not be null");
        if (sizeBytes < 0) throw new BlobValidationException("sizeBytes must not be negative");
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
```

```java
package com.edem.dedup4j.core.storage;

import com.edem.dedup4j.core.exception.BlobValidationException;
import java.time.Instant;

public record StoredBlob(String objectKey, String provider, String bucketOrContainer,
                         long sizeBytes, String contentType, String checksum,
                         Instant createdAt) {
    public StoredBlob {
        if (objectKey == null || objectKey.isBlank()) throw new BlobValidationException("objectKey must not be blank");
        if (provider == null || provider.isBlank()) throw new BlobValidationException("provider must not be blank");
        if (bucketOrContainer == null || bucketOrContainer.isBlank()) throw new BlobValidationException("bucketOrContainer must not be blank");
        if (sizeBytes < 0) throw new BlobValidationException("sizeBytes must not be negative");
    }
}
```

```java
package com.edem.dedup4j.core.storage;

import com.edem.dedup4j.core.exception.BlobValidationException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

public record BlobResource(String objectKey, InputStream content, long sizeBytes,
                           String contentType, Map<String, String> metadata)
        implements AutoCloseable {
    public BlobResource {
        if (objectKey == null || objectKey.isBlank()) throw new BlobValidationException("objectKey must not be blank");
        if (content == null) throw new BlobValidationException("content must not be null");
        if (sizeBytes < 0) throw new BlobValidationException("sizeBytes must not be negative");
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
    @Override public void close() throws IOException { content.close(); }
}
```

- [x] **Step 4: Run the test and verify GREEN**

Run: `./mvnw -pl dedup4j-core -Dtest=StorageModelsTest test`
Expected: four tests pass with zero failures.

### Task 3: Service Command and Reference Models

**Files:**
- Create: `dedup4j-core/src/test/java/com/edem/dedup4j/core/model/CoreModelsTest.java`
- Create: `dedup4j-core/src/main/java/com/edem/dedup4j/core/model/StoreBlobCommand.java`
- Create: `dedup4j-core/src/main/java/com/edem/dedup4j/core/model/BlobReference.java`

- [x] **Step 1: Write failing model tests**

```java
package com.edem.dedup4j.core.model;

import com.edem.dedup4j.core.exception.BlobValidationException;
import com.edem.dedup4j.core.hash.ContentHash;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreModelsTest {
    @Test
    void commandCopiesMetadataAndDoesNotAcceptObjectKeys() {
        Map<String, String> metadata = new HashMap<>(Map.of("tenant", "one"));
        StoreBlobCommand command = new StoreBlobCommand(new ByteArrayInputStream(new byte[0]), "a.txt", "text/plain", 0, metadata);
        metadata.put("tenant", "two");
        assertEquals("one", command.metadata().get("tenant"));
    }

    @Test
    void referenceCarriesContentIdentityAndDuplicateDecision() {
        ContentHash hash = new ContentHash("SHA-256", "abc", 3);
        BlobReference reference = new BlobReference(UUID.randomUUID(), hash, "text/plain", "local", "sha-256/ab/abc", true);
        assertSame(hash, reference.contentHash());
        assertTrue(reference.duplicate());
    }

    @Test
    void rejectsInvalidRequiredModelFields() {
        assertThrows(BlobValidationException.class,
                () -> new StoreBlobCommand(InputStream.nullInputStream(), null, null, -1, Map.of()));
        assertThrows(BlobValidationException.class,
                () -> new BlobReference(null, new ContentHash("SHA-256", "abc", 3), null, "local", "key", false));
    }
}
```

- [x] **Step 2: Run the test and verify RED**

Run: `./mvnw -pl dedup4j-core -Dtest=CoreModelsTest test`
Expected: compilation fails because `StoreBlobCommand` and `BlobReference` do not exist.

- [x] **Step 3: Implement the records**

```java
package com.edem.dedup4j.core.model;

import com.edem.dedup4j.core.exception.BlobValidationException;
import java.io.InputStream;
import java.util.Map;

public record StoreBlobCommand(InputStream content, String filename, String contentType,
                               long sizeBytes, Map<String, String> metadata) {
    public StoreBlobCommand {
        if (content == null) throw new BlobValidationException("content must not be null");
        if (sizeBytes < 0) throw new BlobValidationException("sizeBytes must not be negative");
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
```

```java
package com.edem.dedup4j.core.model;

import com.edem.dedup4j.core.exception.BlobValidationException;
import com.edem.dedup4j.core.hash.ContentHash;
import java.util.UUID;

public record BlobReference(UUID assetContentId, ContentHash contentHash, String contentType,
                            String storageProvider, String objectKey, boolean duplicate) {
    public BlobReference {
        if (assetContentId == null) throw new BlobValidationException("assetContentId must not be null");
        if (contentHash == null) throw new BlobValidationException("contentHash must not be null");
        if (storageProvider == null || storageProvider.isBlank()) throw new BlobValidationException("storageProvider must not be blank");
        if (objectKey == null || objectKey.isBlank()) throw new BlobValidationException("objectKey must not be blank");
    }
}
```

- [x] **Step 4: Run the test and verify GREEN**

Run: `./mvnw -pl dedup4j-core -Dtest=CoreModelsTest test`
Expected: three tests pass with zero failures.

### Task 4: BlobStorage SPI

**Files:**
- Create: `dedup4j-core/src/test/java/com/edem/dedup4j/core/storage/BlobStorageApiTest.java`
- Create: `dedup4j-core/src/main/java/com/edem/dedup4j/core/storage/BlobStorage.java`

- [x] **Step 1: Write the failing API-shape test**

```java
package com.edem.dedup4j.core.storage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BlobStorageApiTest {
    @Test
    void exposesProviderNeutralOperations() throws Exception {
        assertEquals(StoredBlob.class, BlobStorage.class.getMethod("put", PutBlobRequest.class).getReturnType());
        assertEquals(BlobResource.class, BlobStorage.class.getMethod("get", String.class).getReturnType());
        assertEquals(void.class, BlobStorage.class.getMethod("delete", String.class).getReturnType());
        assertEquals(boolean.class, BlobStorage.class.getMethod("exists", String.class).getReturnType());
    }
}
```

- [x] **Step 2: Run the test and verify RED**

Run: `./mvnw -pl dedup4j-core -Dtest=BlobStorageApiTest test`
Expected: compilation fails because `BlobStorage` does not exist.

- [x] **Step 3: Add the SPI**

```java
package com.edem.dedup4j.core.storage;

public interface BlobStorage {
    StoredBlob put(PutBlobRequest request);
    BlobResource get(String objectKey);
    void delete(String objectKey);
    boolean exists(String objectKey);
}
```

- [x] **Step 4: Run the test and verify GREEN**

Run: `./mvnw -pl dedup4j-core -Dtest=BlobStorageApiTest test`
Expected: one test passes with zero failures.

### Task 5: Documentation and Verification

**Files:**
- Modify: `docs/implementation.md`
- Modify: `docs/taskindex.md`
- Modify: `docs/epics/epic-001-core-library/README.md`
- Modify: `docs/epics/epic-001-core-library/tasks/task-004-add-storage-neutral-spi-and-models.md`
- Modify: `docs/changelog.md`

- [x] **Step 1: Run the complete core test suite**

Run: `./mvnw -pl dedup4j-core test`
Expected: all core tests pass with zero failures and zero errors.

- [x] **Step 2: Run the full reactor verification**

Run: `./mvnw verify`
Expected: `BUILD SUCCESS`.

- [x] **Step 3: Inspect the feature diff as required by AGENTS.md**

Run: `git diff HEAD~1 --name-only`
Expected: the feature's core source, tests, planning/status docs, implementation index, and changelog are listed; no provider module or dependency file is present.

- [x] **Step 4: Update only the affected indexed docs**

Add the new entry points and behavior to `docs/implementation.md`, mark task 1.4 complete in the task index and Epic 1 pages, and append:

```markdown
## 2026-07-15 — Add storage-neutral SPI and models

- Added provider-neutral storage contracts, immutable command/result models, and domain exceptions.
- Affected `dedup4j-core` and the Epic 1 planning/status documentation.
```

- [x] **Step 5: Confirm no new architectural decision was made**

The implementation realizes ADR-001 and ADR-004 without changing or reversing their decisions, so do not append a new ADR.

- [x] **Step 6: Commit the completed feature**

```bash
git add dedup4j-core docs
git commit -m "feat(core): add storage-neutral SPI and models"
```
