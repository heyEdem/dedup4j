# Provider Testing

Provider SDKs stay isolated from the provider-neutral core and starter modules. The repository uses two complementary test paths:

## Default verification

These tests require no cloud credentials or external services:

- `ProviderDependencyBoundaryTest` parses the reactor POMs and verifies AWS coordinates are owned only by `dedup4j-storage-s3` and Azure coordinates only by `dedup4j-storage-azure`.
- `CoreModuleBoundaryTest` scans the core test classpath for Spring, JPA, AWS, and Azure packages.
- `S3BlobStorageContractTest` uses an in-process SDK fake.
- `AzureBlobStorageContractTest` uses the Azure SDK against an in-process JDK HTTP server.

Run the boundary check with:

```bash
./mvnw test -Dtest=ProviderDependencyBoundaryTest
```

Run provider tests or the complete reactor with:

```bash
./mvnw -pl dedup4j-storage-s3 test
./mvnw -pl dedup4j-storage-azure test
./mvnw --batch-mode --no-transfer-progress verify
```

## External provider tests

Credential-dependent tests are not part of the default path. When an external S3- or Azure-backed contract test is added, keep it in the corresponding provider module and mark it with JUnit 5's `external-provider` tag. Run it explicitly with Surefire's tag selector after supplying the provider-specific environment and endpoint configuration:

```bash
./mvnw -pl dedup4j-storage-s3 -Dgroups=external-provider test
./mvnw -pl dedup4j-storage-azure -Dgroups=external-provider test
```

Do not add cloud credentials, secrets, or required external endpoints to the normal Maven verification workflow.

The local dashboard is also credential-free: its integration tests use two
in-process management endpoints and a temporary SQLite database. They verify
registration, independent polling/status, aggregate contribution, and bounded
failure retention. They must not connect directly to S3, Azure, or consuming
application databases.

Run the local monitoring verification with:

```bash
./mvnw -pl blob-helper-spring-boot-management,blob-helper-dashboard test
```
