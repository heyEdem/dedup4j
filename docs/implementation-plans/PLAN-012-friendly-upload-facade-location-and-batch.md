# Friendly Upload Facade, Stable Location, and Batch Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give Spring applications a one-line upload API for common file sources, stable provider-neutral locations, and ordered batch results that report every item.

**Architecture:** Provider-neutral location data lives in core and is returned by the advanced deduplication service. A Spring-facing `Dedup4j` façade adapts common input types into `StoreBlobCommand`; it never calls a provider SDK. Batch processing is a deterministic sequential loop over the single-item façade, so each outcome uses the independent transaction created in PLAN-011.

**Tech Stack:** Java 21 records/sealed interfaces, Spring Web `MultipartFile`, Spring Boot auto-configuration, JUnit 5, Mockito, AssertJ.

**Implements:** ADR-009
**Depends on:** PLAN-011

---

## Five-Questions Contract

- **Protected outcome (Q1):** normal upload code is `BlobReference stored = dedup4j.store(file);`, while controllers remain free to return their own DTO or URL.
- **Invariants (Q2):** dedup4j is the only physical uploader, stable location is always present, broad media types work, declared size is enforced, and batches preserve/report every input in order.
- **Owner (Q3):** core owns location, the advanced service owns deduplication/location lookup, the Spring façade owns input adaptation and batching, applications own HTTP and presigning.
- **Proof (Q4):** location mapping, source adaptation, size, delegation, deduplication, and mixed batch-outcome tests below.
- **Exclusions (Q5):** no controller, app entity, S3 URL constructor, presigned URL, parallel batch executor, release/download façade, or Git operation.

## File Map

- Create: `dedup4j-core/src/main/java/com/edem/dedup4j/core/model/BlobLocation.java`.
- Modify: `dedup4j-core/src/main/java/com/edem/dedup4j/core/model/BlobReference.java`.
- Create: `dedup4j-core/src/test/java/com/edem/dedup4j/core/model/BlobLocationTest.java`.
- Modify: `dedup4j-core/src/test/java/com/edem/dedup4j/core/model/CoreModelsTest.java`.
- Modify: `dedup4j-spring-boot-starter/pom.xml` — add Spring Web for `MultipartFile`.
- Modify: `.../service/BlobDeduplicationService.java` — add stable location lookup.
- Modify: `.../service/DefaultBlobDeduplicationService.java` — map container data and validate declared size.
- Modify: `.../service/SpringTransactionalBlobDeduplicationService.java` — transact location lookup.
- Modify: `.../service/BlobReferences.java` — include the complete location.
- Create: `.../facade/Dedup4j.java`.
- Create: `.../facade/DefaultDedup4j.java`.
- Create: `.../facade/BatchStoreOutcome.java`.
- Create: `.../facade/BlobStoreSuccess.java`.
- Create: `.../facade/BlobStoreFailure.java`.
- Create: `.../facade/BatchStoreResult.java`.
- Modify: `.../autoconfigure/service/Dedup4jServiceAutoConfiguration.java` — register the façade.
- Modify: `.../test/java/com/edem/dedup4j/service/BlobDeduplicationServiceContractTest.java`.
- Modify: `.../test/java/com/edem/dedup4j/service/BlobDeduplicationServiceTest.java`.
- Create: `.../test/java/com/edem/dedup4j/facade/DefaultDedup4jTest.java`.
- Create: `.../test/java/com/edem/dedup4j/facade/DefaultDedup4jBatchTest.java`.
- Create: `.../test/java/com/edem/dedup4j/facade/Dedup4jMvcUsageTest.java`.
- Modify: `.../test/java/com/edem/dedup4j/autoconfigure/service/Dedup4jServiceAutoConfigurationTest.java`.

## Acceptance Criteria (from Q4)

- [ ] **BlobLocationTest.requiresCompleteStableIdentity:** provider, bucket/container, and object key are nonblank and value-equal.
- [ ] **BlobDeduplicationServiceTest.newAndDuplicateReturnSameLocation:** new and duplicate results expose the same complete stable location.
- [ ] **BlobDeduplicationServiceContractTest.locationFindsPersistedContent:** ID lookup returns provider/container/key and never invokes storage IO.
- [ ] **DefaultDedup4jTest.multipartDelegatesOnce:** multipart metadata becomes one exact `StoreBlobCommand` and returns the delegate reference unchanged.
- [ ] **DefaultDedup4jTest.acceptsPathBytesAndDescribedStream:** every promised source delegates with correct filename, content type, size, and metadata.
- [ ] **DefaultDedup4jTest.rejectsOversizeBeforeDelegate:** configured maximum size prevents service and provider calls.
- [ ] **BlobDeduplicationServiceTest.rejectsDeclaredSizeMismatch:** the advanced store path rejects streams whose actual byte count differs from the command.
- [ ] **DefaultDedup4jBatchTest.reportsEveryOutcomeInOrder:** a success/failure/success input produces three indexed outcomes in that order and continues after the failure.
- [ ] **DefaultDedup4jBatchTest.duplicateIsSuccess:** duplicate references appear as successful outcomes, not failures.
- [ ] **Dedup4jMvcUsageTest.bindsMultipartArrayWithoutLibraryController:** a test application controller can pass `MultipartFile[]` directly to the façade and map its result.
- [ ] **Dedup4jServiceAutoConfigurationTest.exposesFacade:** one façade is available by interface and backs off for an application-provided façade.

## Out of Scope (from Q5)

- Controller return types or consuming-application record creation.
- Public/private S3 URL construction and presigning.
- Parallel batch execution or all-or-nothing semantics.
- MIME allowlists enabled by default.
- Retain, release, download, and dashboard methods on `Dedup4j`.
- Git operations without Edem's instruction.

## Tasks

### Task 1: Make stable physical location a first-class value

**Files:**

- Create: `dedup4j-core/src/main/java/com/edem/dedup4j/core/model/BlobLocation.java`
- Modify: `dedup4j-core/src/main/java/com/edem/dedup4j/core/model/BlobReference.java`
- Create: `dedup4j-core/src/test/java/com/edem/dedup4j/core/model/BlobLocationTest.java`
- Modify: `dedup4j-core/src/test/java/com/edem/dedup4j/core/model/CoreModelsTest.java`

- [ ] **Step 1: Write failing value-contract tests**

Test blank/null validation for each field, record equality, and `BlobReference.location()`.

- [ ] **Step 2: Add the provider-neutral value**

```java
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
```

- [ ] **Step 3: Extend `BlobReference` compatibly**

Add `String bucketOrContainer` immediately before `objectKey` in the record components, validate it, and add:

```java
public BlobLocation location() {
    return new BlobLocation(storageProvider, bucketOrContainer, objectKey);
}
```

Update every `BlobReference` constructor call and test fixture across the reactor. Do not replace `storageProvider()` or `objectKey()` accessors; they remain useful and source changes are limited to the new constructor component.

- [ ] **Step 4: Run core tests**

```bash
./mvnw -pl dedup4j-core test -Dtest=BlobLocationTest,CoreModelsTest
```

### Task 2: Return locations from store and ID lookup

**Files:**

- Modify: `dedup4j-spring-boot-starter/src/main/java/com/edem/dedup4j/service/BlobDeduplicationService.java`
- Modify: `dedup4j-spring-boot-starter/src/main/java/com/edem/dedup4j/service/DefaultBlobDeduplicationService.java`
- Modify: `dedup4j-spring-boot-starter/src/main/java/com/edem/dedup4j/service/SpringTransactionalBlobDeduplicationService.java`
- Modify: `dedup4j-spring-boot-starter/src/main/java/com/edem/dedup4j/service/BlobReferences.java`
- Modify: `dedup4j-spring-boot-starter/src/test/java/com/edem/dedup4j/service/BlobDeduplicationServiceContractTest.java`
- Modify: `dedup4j-spring-boot-starter/src/test/java/com/edem/dedup4j/service/BlobDeduplicationServiceTest.java`

- [ ] **Step 1: Add failing service-contract tests**

Require both new and duplicate `BlobReference` values to carry `bucketOrContainer`. Add:

```java
BlobLocation location(UUID assetContentId);
```

to the contract test and assert the method reads metadata only; verify `BlobStorage` has no interactions.

- [ ] **Step 2: Centralize metadata mapping**

Implement `BlobReferences.from(AssetContent content, boolean duplicate)` so every result uses:

```java
new BlobReference(
        content.getId(),
        new ContentHash(content.getHashAlgorithm(), content.getContentHash(), content.getSizeBytes()),
        content.getContentType(),
        content.getStorageProvider(),
        content.getBucketOrContainer(),
        content.getObjectKey(),
        duplicate
)
```

Use this helper for existing duplicates, newly persisted content, and PLAN-011's race winner.

- [ ] **Step 3: Add stable location lookup**

Add an unlocked `AssetContentRepository.findById(UUID)` for read-only metadata access. Implement `DefaultBlobDeduplicationService.location` by mapping it to `content.getStorageProvider()`, `content.getBucketOrContainer()`, and `content.getObjectKey()`, throwing `ContentNotFoundException` when absent. Delegate it through the same `REQUIRES_NEW` template in `SpringTransactionalBlobDeduplicationService`.

- [ ] **Step 4: Enforce the described-stream size**

Immediately after `byte[] bytes = readAll(command.content())`, add:

```java
if (bytes.length != command.sizeBytes()) {
    throw new BlobValidationException(
            "Declared size " + command.sizeBytes()
                    + " does not match actual size " + bytes.length
    );
}
```

This makes the explicit `InputStream` length trustworthy and prevents size-limit bypass through a false declaration.

- [ ] **Step 5: Run service tests**

```bash
./mvnw -pl dedup4j-spring-boot-starter test -Dtest=BlobDeduplicationServiceTest,BlobDeduplicationServiceContractTest,SpringTransactionalBlobDeduplicationServiceTest
```

### Task 3: Define the small Spring-facing façade

**Files:**

- Modify: `dedup4j-spring-boot-starter/pom.xml`
- Create: `dedup4j-spring-boot-starter/src/main/java/com/edem/dedup4j/facade/Dedup4j.java`
- Create: `dedup4j-spring-boot-starter/src/main/java/com/edem/dedup4j/facade/DefaultDedup4j.java`
- Create: `dedup4j-spring-boot-starter/src/test/java/com/edem/dedup4j/facade/DefaultDedup4jTest.java`

- [ ] **Step 1: Add Spring Web API and MVC test dependencies**

Add `org.springframework:spring-web` as a compile dependency. The generic starter is already Spring-specific; this supplies `MultipartFile` without adding MVC controllers. Add `spring-webmvc`, `spring-test`, and `jakarta.servlet-api` as test dependencies for the test-only MVC usage fixture in Task 5.

- [ ] **Step 2: Define the exact public surface**

```java
public interface Dedup4j {

    BlobReference store(MultipartFile file);

    BlobReference store(Path path);

    BlobReference store(byte[] content, String filename, String contentType);

    BlobReference store(
            InputStream content,
            long sizeBytes,
            String filename,
            String contentType,
            Map<String, String> metadata
    );

    BatchStoreResult storeAll(MultipartFile[] files);
}
```

The façade intentionally exposes only upload methods. Keep `StoreBlobCommand` and `BlobDeduplicationService` public for advanced integrations.

- [ ] **Step 3: Test source-to-command adaptation**

Capture commands sent to a mocked `BlobDeduplicationService` and assert:

- `MultipartFile`: original filename, reported content type/size, empty metadata;
- `Path`: file name, `Files.probeContentType` or `application/octet-stream`, `Files.size`, empty metadata;
- `byte[]`: caller filename/content type, array length, empty metadata;
- described `InputStream`: every caller-supplied field and copied metadata.

In every case, assert the delegate is called once and its exact `BlobReference` instance is returned.

- [ ] **Step 4: Implement one private delegation path**

`DefaultDedup4j` receives `BlobDeduplicationService` and `Dedup4jProperties`. Every overload constructs a `StoreBlobCommand` and calls one private method:

```java
private BlobReference store(StoreBlobCommand command) {
    long maximum = properties.getDeduplication().getMaxUploadSize().toBytes();
    if (command.sizeBytes() > maximum) {
        throw new BlobValidationException(
                "Upload size " + command.sizeBytes() + " exceeds maximum " + maximum
        );
    }
    return service.store(command);
}
```

Convert source-opening `IOException` to `BlobValidationException` with the source name and cause. Do not inspect or restrict media types in the façade; consuming applications may apply domain-specific validation before calling it, and broad media remains the library default.

- [ ] **Step 5: Run façade adaptation tests**

```bash
./mvnw -pl dedup4j-spring-boot-starter test -Dtest=DefaultDedup4jTest
```

### Task 4: Model ordered partial success explicitly

**Files:**

- Create: `dedup4j-spring-boot-starter/src/main/java/com/edem/dedup4j/facade/BatchStoreOutcome.java`
- Create: `dedup4j-spring-boot-starter/src/main/java/com/edem/dedup4j/facade/BlobStoreSuccess.java`
- Create: `dedup4j-spring-boot-starter/src/main/java/com/edem/dedup4j/facade/BlobStoreFailure.java`
- Create: `dedup4j-spring-boot-starter/src/main/java/com/edem/dedup4j/facade/BatchStoreResult.java`
- Create: `dedup4j-spring-boot-starter/src/test/java/com/edem/dedup4j/facade/DefaultDedup4jBatchTest.java`
- Modify: `dedup4j-spring-boot-starter/src/main/java/com/edem/dedup4j/facade/DefaultDedup4j.java`

- [ ] **Step 1: Define exhaustive outcome types**

```java
public sealed interface BatchStoreOutcome permits BlobStoreSuccess, BlobStoreFailure {
    int index();
    String filename();
}

public record BlobStoreSuccess(
        int index,
        String filename,
        BlobReference reference
) implements BatchStoreOutcome {
    public BlobStoreSuccess {
        if (index < 0) throw new BlobValidationException("index must not be negative");
        Objects.requireNonNull(reference, "reference must not be null");
    }
}

public record BlobStoreFailure(
        int index,
        String filename,
        RuntimeException failure
) implements BatchStoreOutcome {
    public BlobStoreFailure {
        if (index < 0) throw new BlobValidationException("index must not be negative");
        Objects.requireNonNull(failure, "failure must not be null");
    }
}
```

`BatchStoreResult` defensively copies its list and exposes `outcomes()`, `successes()`, `failures()`, and `allSucceeded()`. Preserve outcome order in all derived lists.

- [ ] **Step 2: Test mixed outcomes before implementation**

Use three `MultipartFile` inputs. Make item 0 return new content, item 1 throw `BlobStorageException`, and item 2 return a duplicate reference. Assert all three service calls occur, outcome indexes are `[0, 1, 2]`, the result is success/failure/success, and the duplicate is the second success.

Also test a null element: it becomes a failure at its index and later inputs are still processed. A null array is invalid and throws `BlobValidationException` before processing.

- [ ] **Step 3: Implement sequential continue-on-failure batching**

```java
public BatchStoreResult storeAll(MultipartFile[] files) {
    if (files == null) {
        throw new BlobValidationException("files must not be null");
    }
    List<BatchStoreOutcome> outcomes = new ArrayList<>(files.length);
    for (int index = 0; index < files.length; index++) {
        MultipartFile file = files[index];
        String filename = null;
        try {
            if (file != null) {
                filename = file.getOriginalFilename();
            }
            outcomes.add(new BlobStoreSuccess(index, filename, store(file)));
        } catch (RuntimeException failure) {
            outcomes.add(new BlobStoreFailure(index, filename, failure));
        }
    }
    return new BatchStoreResult(outcomes);
}
```

Do not wrap the loop in a transaction. Each `store(file)` reaches PLAN-011's independently transactional service.

- [ ] **Step 4: Run batch tests**

```bash
./mvnw -pl dedup4j-spring-boot-starter test -Dtest=DefaultDedup4jBatchTest
```

### Task 5: Auto-configure the façade and document application ownership

**Files:**

- Modify: `dedup4j-spring-boot-starter/src/main/java/com/edem/dedup4j/autoconfigure/service/Dedup4jServiceAutoConfiguration.java`
- Modify: `dedup4j-spring-boot-starter/src/test/java/com/edem/dedup4j/autoconfigure/service/Dedup4jServiceAutoConfigurationTest.java`
- Create: `dedup4j-spring-boot-starter/src/test/java/com/edem/dedup4j/facade/Dedup4jMvcUsageTest.java`
- Modify: `README.md`
- Modify: `docs/implementation.md`

- [ ] **Step 1: Register a conditional façade bean**

```java
@Bean
@ConditionalOnMissingBean(Dedup4j.class)
Dedup4j dedup4j(
        BlobDeduplicationService service,
        Dedup4jProperties properties
) {
    return new DefaultDedup4j(service, properties);
}
```

Assert an application-provided `Dedup4j` instance wins without disabling the advanced service graph.

- [ ] **Step 2: Prove natural Spring MVC array binding without adding a library controller**

Create a test-only controller whose endpoint accepts `@RequestPart("files") MultipartFile[] files`, calls `dedup4j.storeAll(files)`, and maps the result to a test response. Submit three multipart files with `MockMvc`; assert the façade receives the same ordered array and the endpoint returns the mapped application response. The controller must live under `src/test` only.

- [ ] **Step 3: Document the correct application flow**

Use this complete example:

```java
public String uploadImage(MultipartFile file) {
    BlobReference stored = dedup4j.store(file);
    BlobLocation location = stored.location();
    uploadRepository.save(new Upload(location.objectKey(), stored.assetContentId()));
    return publicUrlMapper.toUrl(location);
}
```

State immediately below it:

- the application must not call `S3Client.putObject` after `dedup4j.store`;
- the application creates its logical row for every successful call, including duplicates;
- the HTTP response may be a URL, DTO, ID, `BlobReference`, or empty response;
- the stable location is not an access URL;
- the application owns presigned URL creation.

- [ ] **Step 4: Run full relevant verification**

```bash
./mvnw --batch-mode --no-transfer-progress -pl dedup4j-core,dedup4j-spring-boot-starter -am verify
```

Expected: `BUILD SUCCESS`, including single, batch, stable-location, and context tests.

- [ ] **Step 5: Report changed files to Edem**

Do not run Git commands. Provide the path list and test results for Edem's Git workflow.

## Definition of Done

- [ ] Every promised source type stores through one provider-neutral service call.
- [ ] New and duplicate successes always return a complete stable location.
- [ ] No façade method creates a URL or calls an SDK client.
- [ ] Every batch input produces one same-index outcome and failures do not stop later inputs.
- [ ] The consuming application retains full control of logical records and HTTP responses.
- [ ] Full relevant verification passes.
- [ ] No Git operation was performed without explicit permission.
