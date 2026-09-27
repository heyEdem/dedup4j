# dedup4j Integration Add-ons

## Purpose

This is a living decision document for improving dedup4j's consumer-facing Spring Boot integration. Each reservation is discussed separately before it becomes an approved design or implementation task.

The target experience is an upload-only integration that is easy for an application developer to adopt without exposing dedup4j's internal service graph.

## Status Legend

- **Proposed**: captured for discussion; no decision has been made.
- **Accepted**: agreed as part of the integration design.
- **Rejected**: considered but intentionally excluded, with the reason recorded.
- **Deferred**: legitimate, but outside the current upload-only scope.

## Current Integration Friction

Today, the consumer must add both `dedup4j-spring-boot-starter` and `dedup4j-storage-s3`. The `provider: s3` property can select an S3 implementation only if its classes already exist on the runtime classpath. Configuration cannot download or supply a missing Maven dependency.

The current starter validates provider wiring, but does not create the full default runtime graph. A consumer would still need to construct infrastructure such as `S3BlobStorage`, `ContentHasher`, `ObjectKeyStrategy`, JPA services, and `BlobDeduplicationService`. That is too much setup for the default Spring Boot experience.

The current `StoreBlobCommand` and `BlobReference` are useful provider-neutral domain types, but requiring every Spring MVC consumer to construct and expose them would leak library mechanics into application controllers.

## Prioritized Reservations

### P0 — One-dependency installation experience

- **Status:** Accepted
- **Reservation:** A consumer should not need to understand and combine dedup4j's internal modules just to use S3.
- **Current constraint:** YAML selects among implementations already present; it cannot add the AWS SDK or S3 adapter to the classpath.
- **Design question:** Choose between a universal starter that contains all supported providers, a single provider-specific S3 starter that transitively includes the base starter, or the existing base-starter-plus-adapter model.
- **Measured dependency footprint:** With the current project versions, S3 resolves to approximately 55 runtime JARs/16.2 MiB, Azure to 51 runtime JARs/19.8 MiB, and their combined unique runtime classpath to approximately 85 JARs/30.5 MiB. Existing application dependencies may overlap, so the actual incremental packaged size varies.
- **Important distinction:** The starter JAR itself can stay small. The size increase appears in Maven downloads and the consuming application's packaged executable because provider SDKs are transitive dependencies.

#### Packaging approaches

1. **Generic bundled starter:** `dedup4j-spring-boot-starter` transitively supplies the core, JPA integration, and all supported storage adapters. This gives the simplest documentation and lets configuration alone select among bundled providers. It increases every application's dependency, maintenance, vulnerability-scanning, and potential version-conflict surface.
2. **Provider-specific convenience starter:** an application selects one artifact such as `dedup4j-spring-boot-starter-s3`, which transitively supplies the base integration and S3 adapter. This is still one direct consumer dependency and keeps unrelated SDKs out, but introduces several public starter names.
3. **Base starter plus adapter:** retain the current two-dependency assembly. This preserves maximum modular clarity but exposes internal packaging decisions and provides the weakest default developer experience.

- **Decision:** Use `dedup4j-spring-boot-starter` as the single consumer-facing dependency. Keep provider modules internally separate, include the local, S3, and Azure adapters transitively, make every provider configuration conditional on `dedup4j.storage.provider`, and exclude dashboard/management modules because they are optional product features rather than storage implementations.

#### Dependency risk and maintenance

Version mediation is expected in a dependency graph of this size. For example, the current AWS and Azure graphs request some different Netty/Reactor versions, while Spring Boot dependency management resolves one version for the application. This becomes a defect only when the resolved version is incompatible at compile time or runtime.

The current S3 and Azure provider suites pass all 11 tests against the resolved versions. To keep the generic starter safe, the design should include:

1. Import the official AWS and Azure SDK BOMs and test them with the supported Spring Boot dependency set.
2. Add Maven dependency-convergence checks for important shared libraries such as Netty, Jackson, Reactor, and SLF4J.
3. Test one application context containing every bundled provider module while activating one provider at a time.
4. Run provider contract tests and the full build whenever an SDK, Spring Boot, or shared networking dependency changes.
5. Enable automated vulnerability alerts and dependency-update pull requests; fail dependency-review checks for newly introduced high or critical vulnerabilities.
6. Review dependency updates regularly, but release immediately only for relevant security fixes or demonstrated compatibility problems. Routine SDK updates can be grouped into tested maintenance releases.

Bundling providers increases maintenance responsibility, but does not require releasing dedup4j for every upstream SDK version. Consumers receive the pinned, tested dependency set until a dedup4j maintenance release deliberately advances it.

### P0 — Automatic Spring Boot wiring

- **Status:** Accepted
- **Reservation:** Consumers should not manually declare dedup4j's internal infrastructure beans.
- **Desired default:** Based on the selected provider and configuration, auto-configure the storage adapter, standard SHA-256 hasher, hash-based object-key strategy, JPA collaborators, metrics facade, transaction boundary, and default deduplication service.
- **Extension rule:** Use conditional defaults so an advanced consumer can replace a bean deliberately without duplicating the entire configuration.
- **Success criterion:** Normal S3 usage requires configuration properties and injection of one public dedup4j facade, not a custom `GeneralConfig` class.
- **Decision:** Reuse the consuming application's `DataSource`, JPA `EntityManager`, and transaction manager. Register dedup4j's standard infrastructure and service graph conditionally, allowing deliberate application-provided bean overrides.

### P0 — Metadata database and schema ownership

- **Status:** Accepted
- **Reservation:** dedup4j needs durable metadata tables, but the default must remain simple without silently taking unsafe control of an application's production schema.
- **Decision:** Store dedup4j metadata in the consuming application's existing database so every application instance shares one authoritative content identity and reference count. Automatically register the dedup4j entity mappings while keeping production schema mutation explicitly configurable.

#### Storage approaches

1. **Application database:** use the consuming application's existing `DataSource`, JPA `EntityManager`, transaction manager, backup strategy, and high-availability setup. Supply versioned schema migrations and a safe initialization policy. This is the recommended production default.
2. **Private embedded SQLite:** dedup4j owns a local SQLite file and predefined tables. This is lightweight for a single process, but adds another database, separate transactions, file persistence/backup responsibility, SQLite/Hibernate integration, and inconsistent metadata when multiple application instances have separate files.
3. **Pluggable metadata backend:** use the application database by default and offer an optional SQLite metadata adapter later for local tools or truly single-instance applications. This preserves a simple production default without permanently excluding lightweight use cases.

#### SQLite boundary

The standalone dedup4j dashboard can safely use SQLite because it is designed as one local monitoring process with its own history. Upload deduplication metadata has different correctness requirements: every uploading application instance must observe the same content rows and reference counts.

#### Schema initialization policy

dedup4j owns and ships the versioned schema definitions, uses clearly prefixed table names such as `blob_helper_asset_content`, validates the required schema at startup, and provides actionable missing-schema errors.

Configure schema initialization through `dedup4j.persistence.initialize-schema`:

- `embedded` — the default; initialize supported embedded development databases automatically.
- `always` — explicitly authorize dedup4j to install or update its tables in the configured application database.
- `never` — do not mutate the schema; the application applies dedup4j's packaged migrations through Flyway, Liquibase, or its chosen deployment process.

SQLite is deferred as an optional future metadata adapter for local tools and explicitly single-instance applications. It is not the initial production default.

### P1 — One-line Spring upload API

- **Status:** Accepted
- **Reservation:** `MultipartFile -> StoreBlobCommand -> BlobDeduplicationService.store(...)` is correct internally but too ceremonial as the default application code.
- **Decision:** Auto-configure a friendly `Dedup4j` facade with `dedup4j.store(file)` as the normal path. The facade accepts `MultipartFile` and other common sources such as `Path`, `byte[]`, and an explicitly described `InputStream`, derives available metadata, and delegates internally to `BlobDeduplicationService.store(StoreBlobCommand)`.
- **Media policy:** Store arbitrary media bytes by default. Content-type restrictions are optional application configuration rather than a narrow built-in allowlist.
- **Advanced API:** Retain `StoreBlobCommand` and `BlobDeduplicationService` for streaming, batch infrastructure, and custom integrations.

### P1 — Ordered batch upload API

- **Status:** Accepted
- **Reservation:** Applications should be able to submit multiple media items through the same facade without manually looping over the single-item API.
- **Desired default:** Provide `dedup4j.storeAll(MultipartFile[] files)` for natural Spring MVC request binding and return results in the same array order. Every item independently performs hashing, physical deduplication, and reference counting. An advanced `BlobUpload` collection API can support mixed or non-Multipart input sources.
- **Correctness constraint:** A relational transaction cannot atomically roll back an S3 write. The API must expose partial success honestly rather than imply all-or-nothing storage semantics.
- **Decision:** Accept `MultipartFile[]` directly for Spring MVC batch uploads. Process every item sequentially by default, continue after individual failures, preserve input order, and return a `BatchStoreResult` containing one indexed success or failure outcome per input. Allow future concurrency configuration without changing result ordering.
- **Application persistence:** Create consuming-application upload records for successful outcomes only. Each successful duplicate still increments its dedup4j reference count.

### P1 — Application-owned controller responses

- **Status:** Accepted
- **Reservation:** dedup4j should not force an application's upload endpoint to return `BlobReference`.
- **Desired default:** The store operation may return a `BlobReference` as its internal result, but the consuming service decides whether to persist its `assetContentId`, map selected fields, return its existing DTO, or ignore the result in an intentionally upload-only/no-release workflow.
- **Lifecycle note:** An application that may later release content should retain at least `assetContentId` internally. This does not require exposing that ID or the full dedup4j model in the HTTP response.
- **Decision:** `store(...)` returns a non-optional `BlobReference` for both new and duplicate content, but the consuming application owns its HTTP response. An S3 application may map the returned object key to its configured S3 URL, create its logical upload record on every successful call, and return only that URL.
- **Deduplication rule:** `BlobDeduplicationService.store(...)` is the sole physical upload call. A consuming application must not invoke `S3Client.putObject(...)` afterward. Duplicate calls reuse the returned physical object key/URL while incrementing the reference count.

### P1 — Public storage URL mapping

- **Status:** Accepted
- **Reservation:** Some consuming applications want to return an S3 URL after storage without exposing provider-neutral dedup4j models.
- **Constraint:** An ordinary S3 HTTPS URL identifies an object but is directly retrievable only when bucket/object permissions allow public reads. Private content requires a different access mechanism, such as an authenticated application endpoint or a presigned URL, which remains outside the current upload-only scope.
- **Decision:** Persist the stable storage provider, bucket/container, and object key in dedup4j metadata. Do not persist expiring access URLs and do not generate presigned URLs. Consuming applications generate presigned URLs when required and may map the stable location to their own response.

### P1 — Minimal provider configuration

- **Status:** Accepted
- **Reservation:** Common AWS S3 configuration should remain small; MinIO-specific endpoint and path-style properties should be optional.
- **Decision:** Reuse an application-provided `S3Client` when available. Otherwise, conditionally create one through the AWS SDK's standard credential and region chains. Require only `provider` and `bucket`; keep `region`, `endpoint`, and `path-style` as optional overrides for MinIO and specialized deployments.
- **Override rule:** Register provider clients and storage adapters with conditional defaults so advanced applications can supply customized clients without replacing the rest of dedup4j's service graph.

### P2 — Combined observability remains opt-in

- **Status:** Accepted
- **Reservation:** Upload consumers should not receive dashboard or management dependencies unless they choose observability features.
- **Decision:** Keep dashboard and management functionality outside the generic upload starter, but offer one optional `dedup4j-spring-boot-observability` aggregate dependency that installs both for the single-application experience. Their implementation modules and enablement properties remain separate. The standalone multi-instance dashboard remains a different application.

## Scope Guardrails

The current design discussion covers upload and physical deduplication only. It does not add download, retain, release, presigned URLs, reconciliation UI, or dashboard behavior to the basic integration.

For identical uploads, dedup4j continues to keep one physical object and increment the stored reference count. If a consuming application intentionally does not retain the returned content ID and release is not implemented, those references remain intentionally.

## Decision Order

1. Select the one-dependency packaging model.
2. Define the automatic bean and transaction wiring.
3. Define the one-line Spring upload facade.
4. Confirm application-owned response and persistence behavior.
5. Reduce S3/MinIO configuration to the smallest safe property set.
6. Confirm the dashboard's optional packaging boundary.

## Decision Log

- **2026-09-03 — Accepted:** Provide one generic bundled `dedup4j-spring-boot-starter` containing the storage-provider adapters transitively. Acceptance includes the dependency-risk and maintenance safeguards documented above. Dashboard and management remain separate and opt-in.
- **2026-09-03 — Accepted:** Reuse the consuming application's DataSource, JPA EntityManager, and transaction manager, and conditionally auto-configure dedup4j's default provider-neutral service graph.
- **2026-09-03 — Accepted:** Store metadata in the consuming application's database and use guarded `embedded`, `always`, and `never` schema-initialization modes. dedup4j owns versioned schema definitions; SQLite is deferred as an optional local/single-instance adapter.
- **2026-09-03 — Accepted:** Keep controller responses application-owned. dedup4j returns a `BlobReference`, but consumers may persist/map only the fields they need and return their existing DTO or URL. dedup4j remains the sole physical upload path.
- **2026-09-03 — Accepted:** Persist stable physical object locations in dedup4j metadata while leaving presigned URL generation to consuming applications.
- **2026-09-03 — Accepted:** Provide a friendly `Dedup4j` facade for `MultipartFile` and other common upload sources, allow broad media types by default, and keep command/service types as the advanced API.
- **2026-09-03 — Accepted:** Support ordered `MultipartFile[]` batch uploads that continue after failures and report one outcome per item; application records are created only for successful items.
- **2026-09-03 — Accepted:** Reuse an application-provided S3 client or auto-create one from standard AWS configuration; require only provider and bucket while keeping MinIO settings optional.
- **2026-09-03 — Accepted:** Keep dashboard and management implementation modules outside the generic upload starter, and add one optional `dedup4j-spring-boot-observability` aggregate for consumers who want both. Keep the standalone multi-instance dashboard separate.

## Approved Implementation Records

- Overall five-question design: [`plans/2026-09-03-zero-boilerplate-spring-integration-design.md`](plans/2026-09-03-zero-boilerplate-spring-integration-design.md)
- Architecture decisions: [`ADR-007`](adrs/ADR-007-generic-starter-and-provider-autoconfiguration.md), [`ADR-008`](adrs/ADR-008-consumer-database-and-managed-schema.md), [`ADR-009`](adrs/ADR-009-friendly-upload-facade-and-batch-outcomes.md), [`ADR-010`](adrs/ADR-010-combined-observability-starter.md)
- Executable plans: [`PLAN-009`](implementation-plans/PLAN-009-generic-starter-packaging-and-governance.md), [`PLAN-010`](implementation-plans/PLAN-010-provider-client-and-storage-autoconfiguration.md), [`PLAN-011`](implementation-plans/PLAN-011-consumer-jpa-and-schema-autoconfiguration.md), [`PLAN-012`](implementation-plans/PLAN-012-friendly-upload-facade-location-and-batch.md), [`PLAN-013`](implementation-plans/PLAN-013-combined-observability-starter.md)
