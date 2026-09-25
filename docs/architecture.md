# Architecture

## Project Type

Maven multi-module Java 21 library project for a Spring Boot-compatible blob deduplication helper.

Current implementation state: root Maven reactor with `dedup4j-core`, `dedup4j-jpa`, `dedup4j-spring-boot-starter`, `dedup4j-storage-local`, `dedup4j-storage-s3`, `dedup4j-storage-azure`, the optional `dedup4j-spring-boot-management`, `blob-helper-spring-boot-dashboard`, and dependency-only `blob-helper-spring-boot-observability` modules, plus the standalone `blob-helper-dashboard` application. The original Spring Boot shell class still exists under root `src/`, but the root project is now `pom` packaging and the shell source is not part of a reactor child module.

The local dashboard subsystem is implemented and covered by credential-free
multi-instance end-to-end verification, as defined by
[ADR-005](adrs/ADR-005-local-dashboard-pull-monitoring.md).

## Directory Map

```text
.
├── dedup4j-core/
│   ├── pom.xml
│   └── src/
├── dedup4j-jpa/
│   ├── pom.xml
│   └── src/
├── dedup4j-spring-boot-starter/
│   ├── pom.xml
│   └── src/
├── dedup4j-storage-local/
│   ├── pom.xml
│   └── src/
├── dedup4j-storage-s3/
│   ├── pom.xml
│   └── src/
├── dedup4j-storage-azure/
│   ├── pom.xml
│   └── src/
├── dedup4j-spring-boot-management/
│   ├── pom.xml
│   └── src/
├── blob-helper-spring-boot-dashboard/
│   ├── pom.xml
│   └── src/
├── blob-helper-spring-boot-observability/
│   ├── pom.xml
│   └── src/
├── blob-helper-dashboard/
│   ├── pom.xml
│   └── src/
├── docs/
│   ├── adrs/
│   ├── epics/
│   ├── implementation-plans/
│   ├── provider-testing.md
│   ├── SPECIFICATION.md
│   └── taskindex.md
├── .github/
│   ├── dependabot.yml
│   └── workflows/
├── src/
│   ├── main/
│   └── test/
├── pom.xml
└── README.md
```

## Module Overview

| Module/Package | Purpose |
|---|---|
| `dedup4j-core` | Provider-neutral core module. Owns streaming content hashing, deterministic hash-derived object key generation, stable `BlobLocation`/reference models, the storage SPI, command/result models, domain exceptions, and dependency-boundary enforcement, including the reactor-level provider SDK ownership test. |
| `dedup4j-jpa` | Framework-independent relational metadata module. Owns the `AssetContent` JPA mapping, content-identity uniqueness, physical object metadata, timestamps, optimistic-lock state, transaction-scoped repository lookups/locks, create-or-retain duplicate-key retry, lock-aware reference mutation, and final-reference delete delegation; uses provider-neutral contracts and exceptions from `dedup4j-core`. |
| `dedup4j-spring-boot-starter` | Standard Spring Boot upload dependency. Owns `blob-helper.*` configuration binding, selected-provider client/storage auto-configuration, consumer JPA entity discovery and conditional service assembly, guarded Liquibase schema lifecycle, per-operation Spring transactions, the provider-neutral `Dedup4j` upload façade and ordered batch outcomes, final provider validation, and optional Micrometer metrics; it transitively includes the local, S3, and Azure adapter modules but declares no provider SDK coordinates or REST controllers. |
| `dedup4j-storage-local` | Local filesystem storage adapter module. Owns local provider configuration (`LocalBlobStorageProperties` with configurable root directory) and the `LocalBlobStorage` adapter implementing put, get, idempotent delete, and exists with normalized key resolution that rejects path traversal outside the root; depends only on `dedup4j-core` with no cloud SDKs. |
| `dedup4j-storage-s3` | AWS S3 provider module. Owns the module-local AWS SDK v2 dependency management, S3 connection properties, and `S3BlobStorage` adapter implementing the provider-neutral `BlobStorage` contract with streaming access and domain exception mapping. |
| `dedup4j-storage-azure` | Azure Blob Storage provider module. Owns the module-local Azure SDK BOM and Blob SDK dependency, Azure connection properties, and `AzureBlobStorage`, which implements streaming put/get, idempotent delete, existence checks, and provider-to-core exception mapping without exposing Azure types through core. |
| `dedup4j-spring-boot-management` | Optional instance-side management module. Owns local read-only information, health, metrics, and failure endpoints plus management properties; it does not own application assets, blob bytes, or provider credentials. |
| `blob-helper-spring-boot-dashboard` | Optional embedded single-instance dashboard starter. Owns dashboard properties, current-process snapshots, read-only API routes, and packaged static UI; it does not own SQLite history or instance registration. |
| `blob-helper-spring-boot-observability` | Optional empty-code aggregate JAR that depends on the management and embedded-dashboard modules so one consumer dependency supplies the embedded current-application UI/API; it does not include the standalone fleet dashboard. |
| `blob-helper-dashboard` | Standalone local monitoring application. Owns multi-instance registration, pull polling, SQLite aggregate history, seven-day failure retention, read-only REST views, and the static light/dark UI. |
| root `pom.xml` | Maven reactor parent with Java 21, JUnit and Spring Boot BOMs, compiler/Surefire plugin management, and Enforcer dependency-convergence validation for shared SDK infrastructure. |
| root `src/main/java/com/edem/blobhelper` | Legacy Spring Boot shell application class from project creation. Not currently part of a reactor child module. |
| `.github/workflows/ci.yml` | GitHub Actions CI workflow for Java 21 Maven verification. |
| `.github/dependabot.yml` | Weekly update proposals for Maven and GitHub Actions dependencies. |
| `.github/workflows/dependency-review.yml` | Pull-request gate that rejects newly introduced high/critical vulnerable dependencies. |
| `docs/adrs` | Architecture decisions for content identity, upload/ref-counting, release/reconciliation, and pluggable storage. |
| `docs/implementation-plans` | Phase-level implementation plans for core, JPA, starter, local storage, S3/Azure, and operations. |
| `docs/epics` | Task breakdown by implementation epic. |

## Data Flow

Planned deduplicated upload flow:

```text
Application upload
  -> BlobDeduplicationService
  -> streaming SHA-256 hash
  -> AssetContent lookup by hash_algorithm + content_hash + size_bytes
  -> duplicate: increment ref_count, skip physical upload
  -> new: generate hash-derived object key, write through BlobStorage, create AssetContent
  -> application creates its own logical asset pointing to AssetContent
```

Planned delete flow:

```text
Application deletes logical asset
  -> Blob Helper release(assetContentId)
  -> lock AssetContent
  -> decrement ref_count
  -> if final reference: delete physical object through BlobStorage
```

Planned monitoring flow:

```text
Blob Helper instance starts
  -> optional management module self-registers with local dashboard
  -> dashboard polls read-only management endpoints
  -> dashboard stores aggregate snapshots and recent failures in SQLite
  -> static UI displays per-instance and combined trends
```

## External Dependencies

| Name | Purpose |
|---|---|
| Java 21 | Project language/runtime target. |
| Maven | Build and module orchestration. |
| `dedup4j-core` | Reactor dependency that supplies provider-neutral content-not-found and reference-count-underflow exceptions to `dedup4j-jpa`. |
| `dedup4j-jpa` | Reactor dependency used by the starter service facade for metadata lookups and reference-count mutation. |
| JUnit Jupiter | Unit testing. |
| Maven Enforcer Plugin | Rejects Spring, JPA, AWS SDK, and Azure SDK dependencies from `dedup4j-core`, including transitive dependencies. |
| Jakarta Persistence 3.2 | Portable entity mapping API used by `dedup4j-jpa`. |
| Hibernate ORM 7.4 | Test-scope JPA provider used to verify entity mappings. |
| H2 2.4 | Test-scope in-memory database for JPA mapping tests. |
| Spring Boot 4.1.1 | `dedup4j-spring-boot-starter` auto-configuration, properties binding, and configuration metadata generation. |
| Spring Data JPA / Spring ORM / Spring JDBC | Starter runtime integration with the consumer's DataSource, shared entity manager, transaction manager, and schema validation. The consumer supplies its database driver. |
| Spring Boot Liquibase starter | Boot 4 Liquibase auto-configuration APIs used alongside Blob Helper's dedicated migration bean for consumer changelog coexistence. |
| Liquibase Core | Starter-owned, versioned migrations for `blob_helper_asset_content`, with separate Blob Helper changelog/lock tables and `embedded`, `always`, and `never` policies. |
| Jackson 3 | Embedded and standalone dashboard JSON handling through the Boot 4 Jackson starter; standalone polling uses the Jackson 3 mapper API. |
| Micrometer Core | Optional starter-module metrics registry API for upload, deduplication, latency, cleanup-failure, and repair instrumentation. |
| spring-boot-test / AssertJ | Test-scope only in the starter module: `ApplicationContextRunner` context tests and fluent failure assertions. |
| `dedup4j-storage-local`, `dedup4j-storage-s3`, `dedup4j-storage-azure` | Compile dependencies of the standard starter. Each adapter owns its provider SDK coordinates and BOM; the starter aggregates adapter modules without declaring SDKs directly. |
| SLF4J API | Starter logging facade for provider-neutral operational events; application logging backends remain consumer-configured. |
| AWS SDK for Java 2.x 2.54.4 | Declared and versioned only by `dedup4j-storage-s3` through its module-local BOM and `software.amazon.awssdk:s3` dependency; available transitively from the standard starter but never declared there or in core. |
| Azure SDK for Java 1.3.8 BOM / Blob SDK 12.35.0 | Declared and versioned only by `dedup4j-storage-azure` through its module-local BOM and `com.azure:azure-storage-blob` dependency; available transitively from the standard starter but never declared there or in core. |
| SQLite JDBC / Spring JDBC | Dashboard-only persistence for local instance registrations, interval metric snapshots, and seven-day failure details. |
| Spring MVC | Embedded dashboard’s conditional read-only controller and resource handler; supplied by a consuming web application. |
| Spring Web | `MultipartFile` input type used by the starter’s upload façade; the starter does not add controllers or HTTP response types. |
| `dedup4j-spring-boot-management`, `blob-helper-spring-boot-dashboard` | Direct compile dependencies of the empty-code observability aggregate; their existing auto-configuration registrations and resources remain authoritative. |
