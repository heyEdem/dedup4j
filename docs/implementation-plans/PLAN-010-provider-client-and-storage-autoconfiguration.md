# Provider Client and Storage Auto-configuration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Configure exactly one local, S3, or Azure `BlobStorage` automatically from `blob-helper.storage.*`, reusing application provider clients before creating defaults.

**Architecture:** Starter-owned provider auto-configurations bind provider settings but delegate physical IO to the existing provider modules. Each configuration is activated by an exact provider property and uses conditional beans. The existing final validator remains the clear failure boundary for missing, unsupported, or ambiguous wiring.

**Tech Stack:** Java 21, Spring Boot 3.5 auto-configuration, AWS SDK v2, Azure Blob SDK, JUnit 5, `ApplicationContextRunner`.

**Implements:** ADR-007

**Implementation status:** Complete and verified locally on 2026-09-05. The task steps below retain the original implementation instructions; the acceptance checklist and verification record describe the delivered result.

---

## Five-Questions Contract

- **Protected outcome (Q1):** provider plus bucket/container configuration yields one usable storage bean with no consumer `@Bean` methods.
- **Invariants (Q2):** one selected provider, application clients win, unselected providers stay inert, S3 bucket-only setup uses AWS defaults.
- **Owner (Q3):** starter provider auto-configuration; adapter modules retain IO.
- **Proof (Q4):** focused property/client/context tests below.
- **Exclusions (Q5):** no provider IO in auto-configuration, no credentials in properties/tests, no default cloud network calls, no Git operations.

## File Map

- Modify: `blob-helper-spring-boot-starter/src/main/java/com/edem/blobhelper/autoconfigure/BlobHelperProperties.java` — add local/S3/Azure nested properties.
- Create: `.../autoconfigure/storage/LocalBlobStorageAutoConfiguration.java`.
- Create: `.../autoconfigure/storage/S3BlobStorageAutoConfiguration.java`.
- Create: `.../autoconfigure/storage/AzureBlobStorageAutoConfiguration.java`.
- Modify: `dedup4j-storage-azure/src/main/java/com/edem/dedup4j/storage/azure/AzureBlobStorage.java` — expose its existing client factory to auto-configuration.
- Modify: `.../autoconfigure/BlobHelperAutoConfiguration.java` — require an explicit provider and validate the final bean graph by type, not bean-name substring.
- Modify: `blob-helper-spring-boot-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.
- Modify: `.../autoconfigure/BlobHelperPropertiesTest.java`.
- Create: `.../autoconfigure/storage/LocalBlobStorageAutoConfigurationTest.java`.
- Create: `.../autoconfigure/storage/S3BlobStorageAutoConfigurationTest.java`.
- Create: `.../autoconfigure/storage/AzureBlobStorageAutoConfigurationTest.java`.
- Modify: `.../autoconfigure/BlobHelperAutoConfigurationTest.java`.
- Create: `.../autoconfigure/ProviderAutoConfigurationDiscoveryTest.java` — verify all providers on one classpath and automatic Spring Boot discovery.

## Acceptance Criteria (from Q4)

- [x] **BlobHelperPropertiesTest.bindsAllProviderPropertiesUnderStorage:** local, S3, and Azure nested properties bind under `blob-helper.storage`.
- [x] **LocalBlobStorageAutoConfigurationTest / ProviderAutoConfigurationDiscoveryTest:** local creates exactly one adapter and no cloud client.
- [x] **S3BlobStorageAutoConfigurationTest.reusesApplicationClient:** an application `S3Client` is the adapter client and no second client exists.
- [x] **S3BlobStorageAutoConfigurationTest.createsDefaultClientWithoutNetwork:** with provider, bucket, and an AWS region-chain value, context creates one S3 client and storage bean without network access.
- [x] **S3BlobStorageAutoConfigurationTest.bindsMinioOverridesIntoClientAndProperties:** endpoint and region reach the constructed client; path-style reaches translated adapter properties and is passed to the SDK builder.
- [x] **AzureBlobStorageAutoConfigurationTest.reusesApplicationClient:** an application `BlobContainerClient` takes precedence.
- [x] **ProviderAutoConfigurationDiscoveryTest:** all adapters are on the classpath but exactly one `BlobStorage` exists for each selected provider through automatic imports discovery.
- [x] **BlobHelperAutoConfigurationTest:** missing/unsupported provider fails with an actionable message.

## Out of Scope (from Q5)

- Changes to `BlobStorage` IO contracts or provider exception mapping.
- Credential properties for AWS; use the AWS credential provider chain.
- External S3/Azure calls in ordinary tests.
- Presigned URLs.
- Dashboard or management auto-configuration.
- Git operations without Edem's instruction.

## Tasks

### Task 1: Bind all provider settings under one namespace

**Files:**

- Modify: `blob-helper-spring-boot-starter/src/main/java/com/edem/blobhelper/autoconfigure/BlobHelperProperties.java`
- Modify: `blob-helper-spring-boot-starter/src/test/java/com/edem/blobhelper/autoconfigure/BlobHelperPropertiesTest.java`

- [ ] **Step 1: Add a failing binding test**

Bind these values and assert every value exactly:

```text
blob-helper.storage.provider=s3
blob-helper.storage.local.root-directory=build/blobs
blob-helper.storage.s3.bucket=media
blob-helper.storage.s3.region=eu-west-1
blob-helper.storage.s3.endpoint=http://localhost:9000
blob-helper.storage.s3.path-style=true
blob-helper.storage.azure.container=media
blob-helper.storage.azure.connection-string=UseDevelopmentStorage=true
blob-helper.storage.azure.endpoint=http://127.0.0.1:10000/devstoreaccount1
blob-helper.storage.azure.account-name=devstoreaccount1
```

- [ ] **Step 2: Run the test and confirm missing nested properties**

```bash
./mvnw -pl blob-helper-spring-boot-starter test -Dtest=BlobHelperPropertiesTest
```

Expected: compilation/assertion failure for the absent provider property groups.

- [ ] **Step 3: Add nested property groups**

`BlobHelperProperties.Storage` must expose `getLocal()`, `getS3()`, and `getAzure()`. Use these exact fields:

Declare `Local`, `S3`, and `Azure` as sibling static classes directly inside `BlobHelperProperties`; `Storage` owns one instance of each type.

```java
public static class Local {
    private Path rootDirectory = Path.of("blob-helper-storage");
    // standard getter and non-null setter
}

public static class S3 {
    private String bucket;
    private String region;
    private URI endpoint;
    private boolean pathStyle;
    // standard getters and setters
}

public static class Azure {
    private String container;
    private String connectionString;
    private URI endpoint;
    private String accountName;
    // standard getters and setters
}
```

- [ ] **Step 4: Re-run the property test**

Expected: `BlobHelperPropertiesTest` passes.

### Task 2: Auto-configure local storage

**Files:**

- Create: `blob-helper-spring-boot-starter/src/main/java/com/edem/blobhelper/autoconfigure/storage/LocalBlobStorageAutoConfiguration.java`
- Create: `blob-helper-spring-boot-starter/src/test/java/com/edem/blobhelper/autoconfigure/storage/LocalBlobStorageAutoConfigurationTest.java`

- [ ] **Step 1: Write context tests for selected, unselected, and overridden local storage**

Assert:

```java
assertThat(context).hasSingleBean(BlobStorage.class);
assertThat(context).hasSingleBean(LocalBlobStorage.class);
assertThat(context.getBean(LocalBlobStorageProperties.class).getRootDirectory())
        .isEqualTo(Path.of("build/blobs"));
```

Also supply an application `BlobStorage` and assert `isSameAs(customStorage)`.

- [ ] **Step 2: Implement the conditional configuration**

```java
@AutoConfiguration(before = BlobHelperAutoConfiguration.class)
@ConditionalOnProperty(prefix = "blob-helper.storage", name = "provider", havingValue = "local")
public class LocalBlobStorageAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    LocalBlobStorageProperties localBlobStorageProperties(BlobHelperProperties properties) {
        LocalBlobStorageProperties target = new LocalBlobStorageProperties();
        target.setRootDirectory(properties.getStorage().getLocal().getRootDirectory());
        return target;
    }

    @Bean(name = "localBlobStorage")
    @ConditionalOnMissingBean(BlobStorage.class)
    LocalBlobStorage localBlobStorage(LocalBlobStorageProperties properties) {
        return new LocalBlobStorage(properties);
    }
}
```

- [ ] **Step 3: Run the focused local test**

```bash
./mvnw -pl blob-helper-spring-boot-starter test -Dtest=LocalBlobStorageAutoConfigurationTest
```

Expected: all local selection/back-off scenarios pass.

### Task 3: Auto-configure S3 with application-client precedence

**Files:**

- Create: `blob-helper-spring-boot-starter/src/main/java/com/edem/blobhelper/autoconfigure/storage/S3BlobStorageAutoConfiguration.java`
- Create: `blob-helper-spring-boot-starter/src/test/java/com/edem/blobhelper/autoconfigure/storage/S3BlobStorageAutoConfigurationTest.java`

- [ ] **Step 1: Write failing context tests**

Cover application client reuse, default client creation, bucket validation, MinIO overrides, and the unselected-provider no-bean case. Set `aws.region=us-east-1` only around the default-client test and restore the original system property in `finally`.

- [ ] **Step 2: Implement S3 property translation and client fallback**

```java
@AutoConfiguration(before = BlobHelperAutoConfiguration.class)
@ConditionalOnClass(S3Client.class)
@ConditionalOnProperty(prefix = "blob-helper.storage", name = "provider", havingValue = "s3")
public class S3BlobStorageAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    S3BlobStorageProperties s3BlobStorageProperties(BlobHelperProperties properties) {
        BlobHelperProperties.S3 source = properties.getStorage().getS3();
        if (source.getBucket() == null || source.getBucket().isBlank()) {
            throw new IllegalStateException(
                    "blob-helper.storage.s3.bucket is required when provider=s3"
            );
        }
        S3BlobStorageProperties target = new S3BlobStorageProperties();
        target.setBucket(source.getBucket());
        target.setRegion(source.getRegion());
        target.setEndpointOverride(source.getEndpoint());
        target.setPathStyleAccess(source.isPathStyle());
        return target;
    }

    @Bean
    @ConditionalOnMissingBean(S3Client.class)
    S3Client blobHelperS3Client(S3BlobStorageProperties properties) {
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

    @Bean(name = "s3BlobStorage", destroyMethod = "")
    @ConditionalOnMissingBean(BlobStorage.class)
    S3BlobStorage s3BlobStorage(S3Client client, S3BlobStorageProperties properties) {
        return new S3BlobStorage(client, properties);
    }
}
```

The empty storage destroy method prevents the adapter from closing an application-owned client; Spring manages the client bean lifecycle.

- [ ] **Step 3: Run the focused S3 test**

```bash
./mvnw -pl blob-helper-spring-boot-starter test -Dtest=S3BlobStorageAutoConfigurationTest
```

Expected: all tests pass without an external S3 call.

### Task 4: Auto-configure Azure with application-client precedence

**Files:**

- Create: `blob-helper-spring-boot-starter/src/main/java/com/edem/blobhelper/autoconfigure/storage/AzureBlobStorageAutoConfiguration.java`
- Create: `blob-helper-spring-boot-starter/src/test/java/com/edem/blobhelper/autoconfigure/storage/AzureBlobStorageAutoConfigurationTest.java`
- Modify: `dedup4j-storage-azure/src/main/java/com/edem/dedup4j/storage/azure/AzureBlobStorage.java`

- [ ] **Step 1: Write failing context tests**

Cover an application-provided `BlobContainerClient`, a development connection-string fallback, required container validation, and no Azure beans when another provider is selected.

- [ ] **Step 2: Implement Azure property and adapter beans**

```java
@AutoConfiguration(before = BlobHelperAutoConfiguration.class)
@ConditionalOnClass(BlobContainerClient.class)
@ConditionalOnProperty(prefix = "blob-helper.storage", name = "provider", havingValue = "azure")
public class AzureBlobStorageAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    AzureBlobStorageProperties azureBlobStorageProperties(BlobHelperProperties properties) {
        BlobHelperProperties.Azure source = properties.getStorage().getAzure();
        if (source.getContainer() == null || source.getContainer().isBlank()) {
            throw new IllegalStateException(
                    "blob-helper.storage.azure.container is required when provider=azure"
            );
        }
        AzureBlobStorageProperties target = new AzureBlobStorageProperties();
        target.setContainer(source.getContainer());
        target.setConnectionString(source.getConnectionString());
        target.setEndpoint(source.getEndpoint());
        target.setAccountName(source.getAccountName());
        return target;
    }

    @Bean
    @ConditionalOnMissingBean(BlobContainerClient.class)
    BlobContainerClient blobHelperAzureContainerClient(AzureBlobStorageProperties properties) {
        return AzureBlobStorage.createClient(properties);
    }

    @Bean(name = "azureBlobStorage")
    @ConditionalOnMissingBean(BlobStorage.class)
    AzureBlobStorage azureBlobStorage(
            BlobContainerClient client,
            AzureBlobStorageProperties properties
    ) {
        return new AzureBlobStorage(client, properties);
    }
}
```

Change the existing `AzureBlobStorage.createClient(AzureBlobStorageProperties)` method from `private static` to `public static`. Its validation and builder logic remain unchanged, so the adapter constructor and Spring auto-configuration share one client-construction path.

- [ ] **Step 3: Run the focused Azure test**

```bash
./mvnw -pl dedup4j-storage-azure,blob-helper-spring-boot-starter -am test -Dtest=AzureBlobStorageAutoConfigurationTest,AzureBlobStorageContractTest
```

Expected: configuration and existing provider contracts pass without Azure credentials.

### Task 5: Register configurations and make provider selection explicit

**Files:**

- Modify: `blob-helper-spring-boot-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Modify: `blob-helper-spring-boot-starter/src/main/java/com/edem/blobhelper/autoconfigure/BlobHelperAutoConfiguration.java`
- Modify: `blob-helper-spring-boot-starter/src/test/java/com/edem/blobhelper/autoconfigure/BlobHelperAutoConfigurationTest.java`

- [ ] **Step 1: Register configurations in provider-before-validator order**

```text
com.edem.blobhelper.autoconfigure.storage.LocalBlobStorageAutoConfiguration
com.edem.blobhelper.autoconfigure.storage.S3BlobStorageAutoConfiguration
com.edem.blobhelper.autoconfigure.storage.AzureBlobStorageAutoConfiguration
com.edem.blobhelper.autoconfigure.BlobHelperAutoConfiguration
```

- [ ] **Step 2: Replace bean-name matching with explicit selection guarantees**

Require `blob-helper.storage.provider`, reject values outside `local`, `s3`, and `azure`, then assert `beanFactory.getBeanNamesForType(BlobStorage.class).length == 1`. Provider configurations already guarantee which adapter is constructed; bean names are not a semantic selector.

- [ ] **Step 3: Test all providers on one classpath**

Run:

```bash
./mvnw -pl blob-helper-spring-boot-starter test -Dtest='*BlobStorageAutoConfigurationTest,BlobHelperAutoConfigurationTest'
```

Expected: each selected provider yields one storage bean; missing/unsupported/ambiguous cases fail clearly.

- [ ] **Step 4: Run module verification**

```bash
./mvnw --batch-mode --no-transfer-progress -pl blob-helper-spring-boot-starter -am verify
```

Expected: `BUILD SUCCESS` with no network credentials.

- [ ] **Step 5: Report changed files to Edem**

Do not run Git commands. Provide the path list and test results for Edem's Git workflow.

## Definition of Done

- [x] All acceptance criteria pass.
- [x] Each provider is activated only by its matching property value (case-insensitive Spring comparison; no whitespace trimming).
- [x] Application provider clients override defaults.
- [x] S3 requires no Blob Helper credential properties.
- [x] Auto-configuration performs no physical storage call during startup.
- [x] No Git mutation was performed; read-only inspection followed repository instructions.

## Implementation review notes — 2026-09-05

- Each provider configuration enables `BlobHelperProperties` itself so it can be loaded independently in a context test or consumer configuration.
- An application `BlobStorage` suppresses the entire default provider graph, including translated properties and cloud clients. Backing off only the adapter would still require unused cloud configuration.
- Provider comparison follows Spring's case-insensitive property condition without trimming surrounding whitespace; the final validator and activation conditions must agree.
- S3 client lifecycle belongs to Spring; inferred destruction is disabled on the auto-configured storage wrapper to prevent duplicate client closure.
- Verification includes automatic `AutoConfiguration.imports` discovery in addition to explicitly loaded provider contexts.
- Changes remain on the existing branch for local handoff. Read-only Git inspection follows the repository instructions; no commit, push, or pull request is part of this plan's execution.

## Verification record — 2026-09-05

`./mvnw --batch-mode --no-transfer-progress verify` completed with `BUILD SUCCESS`: 137 tests, zero failures, errors, or skips across all nine child modules. The starter contributes 52 tests, including provider selection, overrides, required settings, client identity/lifecycle, and imports discovery. Existing local/S3/Azure contracts and management/dashboard verification also pass. Tests use local fakes and Azure development-storage configuration; no external cloud access was exercised.

The MinIO test inspects the SDK's public endpoint/region configuration and the translated path-style property. The public SDK configuration does not expose the force-path-style setting directly; the provider configuration passes it to `S3ClientBuilder.forcePathStyle`.
