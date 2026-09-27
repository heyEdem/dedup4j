# Zero-Boilerplate Spring Integration Design

**Status:** Approved
**Date:** 2026-09-03
**Source:** `docs/integration-add-ons.md`

## Workstream 1 — Generic starter and provider selection

### Q1 — What outcome are we protecting?

A Spring Boot application adds one `dedup4j-spring-boot-starter` dependency, selects a storage provider in configuration, and receives a working provider without manually assembling dedup4j modules or beans.

### Q2 — What must never break?

- Provider SDK dependencies remain declared only by their provider adapter modules.
- Only the configured provider creates its client and `BlobStorage` bean.
- An application-provided provider client takes precedence over the default client.
- Unselected providers do not resolve credentials, create clients, or require configuration.
- Dashboard and management dependencies do not enter the generic upload starter.
- AWS/Azure BOM alignment, dependency convergence, provider contracts, and vulnerability checks guard the bundled dependency graph.

### Q3 — Where should this logic live?

- Transitive consumer dependency assembly: `dedup4j-spring-boot-starter/pom.xml`.
- Conditional provider wiring and property binding: `dedup4j-spring-boot-starter` auto-configuration package.
- Provider SDK implementation code: existing `dedup4j-storage-s3` and `dedup4j-storage-azure` modules only.
- Dependency convergence and security automation: root Maven build and `.github` configuration.

### Q4 — What test proves the rule?

- A starter classpath test sees local, S3, and Azure adapters from one dependency.
- Provider context tests activate local, S3, and Azure one at a time and assert exactly one `BlobStorage` bean.
- A custom `S3Client` test proves the application bean is reused.
- An S3 default-client test proves bucket-only configuration starts without network access.
- Dependency convergence and provider ownership checks pass in `verify`.

### Q5 — What should AI not touch?

- Do not move SDK implementation code into `dedup4j-core` or the starter.
- Do not bundle dashboard or management into the generic upload starter.
- Do not add credentials to dedup4j configuration examples.
- Do not require external cloud credentials in the default test suite.

## Workstream 2 — Consumer JPA integration and schema lifecycle

### Q1 — What outcome are we protecting?

dedup4j uses the consuming application's database and Spring-managed JPA infrastructure without a consumer-written `GeneralConfig`, while production teams retain explicit control over schema mutation.

### Q2 — What must never break?

- Every application instance observes the same content identity and reference counts.
- Standard beans are conditional and remain individually replaceable.
- Public store operations execute inside explicit Spring-managed metadata transactions.
- Duplicate-key retry never calls `EntityManager.getTransaction()` on Spring's shared entity manager.
- `embedded` initializes supported embedded databases, `always` explicitly authorizes initialization, and `never` performs no schema mutation.
- Missing or incompatible schema produces an actionable startup failure.
- SQLite is not the default production metadata store.

### Q3 — Where should this logic live?

- Entity/repository/mutation behavior: `dedup4j-jpa`.
- Bean creation, transaction templates, entity discovery, and schema lifecycle: `dedup4j-spring-boot-starter`.
- Versioned changelog resources: starter `src/main/resources/db/blob-helper`.

### Q4 — What test proves the rule?

- A minimal H2/Spring Boot context starts with no application dedup4j configuration class and exposes the full service graph.
- Application-provided beans replace individual defaults.
- A duplicate-key race under Spring transactions converges on one row and increments once per successful call.
- Schema-mode tests prove `embedded`, `always`, and `never` behavior and validate the prefixed table.
- A missing table in `never` mode fails startup with the property and migration resource in the message.

### Q5 — What should AI not touch?

- Do not create or assume consuming-application logical asset tables.
- Do not use private SQLite storage for production metadata.
- Do not silently mutate an external database unless mode is `always`.
- Do not put provider IO in JPA entity callbacks.

## Workstream 3 — Friendly upload facade, stable locations, and batch behavior

### Q1 — What outcome are we protecting?

Applications store one or many media inputs with a one-line facade while dedup4j remains the sole physical uploader and the application retains control of logical records and HTTP responses.

### Q2 — What must never break?

- `store(file)` performs exactly one physical write for new bytes and no physical write for duplicates.
- Every successful call, including a duplicate, increments or creates exactly one reference.
- `MultipartFile`, `Path`, `byte[]`, and described `InputStream` inputs are supported.
- Media types are unrestricted by default; configured size limits are enforced before storage.
- `storeAll(MultipartFile[])` processes every element, preserves input order, continues after failures, and reports every outcome.
- Batch processing never claims cross-resource atomicity.
- dedup4j persists and exposes stable provider/bucket-or-container/object-key locations but never creates presigned URLs.
- Consuming controllers are never forced to return `BlobReference`.

### Q3 — Where should this logic live?

- Stable location model: `dedup4j-core`.
- Low-level store and location lookup: `BlobDeduplicationService` and its default implementation.
- Spring input adaptation, size validation, and batch orchestration: public `Dedup4j` facade in the starter.
- Presigned URL generation and logical upload records: consuming application.

### Q4 — What test proves the rule?

- Facade tests map each supported input type into the same command metadata.
- Duplicate facade calls produce one physical write, two successful references, and the same stable location.
- Batch tests cover mixed new/duplicate/failure inputs, continued processing, ordered results, and one outcome per index.
- Location lookup returns provider, bucket/container, and key without returning an access URL.
- An MVC integration test accepts `MultipartFile[]` without exposing a dedup4j controller.

### Q5 — What should AI not touch?

- Do not add dedup4j REST upload controllers.
- Do not generate or persist presigned URLs.
- Do not call provider clients from the facade.
- Do not create consuming-application upload/history entities.
- Do not add retain, release, download, or access-URL facade features to this upload-focused change.

## Workstream 4 — Combined optional observability dependency

### Q1 — What outcome are we protecting?

A developer adds one optional observability dependency to install the read-only management API and embedded single-instance dashboard together.

### Q2 — What must never break?

- The generic upload starter remains free of controllers and UI resources.
- Management and dashboard internals remain separate modules.
- The observability aggregate performs no blob mutations.
- Existing enable/disable properties keep their safety behavior.
- The standalone multi-instance dashboard remains a separate executable with SQLite history and polling.

### Q3 — Where should this logic live?

- Aggregate dependency: new `dedup4j-spring-boot-observability` module.
- Management endpoints: existing management module.
- Embedded UI: existing dashboard starter.
- Fleet monitoring: existing standalone dashboard application.

### Q4 — What test proves the rule?

- The observability artifact transitively supplies both internal modules.
- A servlet context with the aggregate dependency serves the embedded dashboard.
- Management endpoints remain disabled by default and become available when explicitly enabled.
- No POST, PUT, PATCH, or DELETE management/dashboard mutation route exists.
- The standalone dashboard tests remain unchanged and passing.

### Q5 — What should AI not touch?

- Do not merge source packages from the management and dashboard modules.
- Do not add SQLite, polling, or fleet registration to embedded mode.
- Do not add observability dependencies to the generic upload starter.
- Do not change the standalone dashboard's storage or polling contracts.

## Approved decomposition

The design is implemented through ADR-007 through ADR-010 and PLAN-009 through PLAN-013. Each plan is independently testable and must preserve all exclusion zones above.

Recommended execution order:

1. PLAN-009 — establish the generic starter classpath and dependency safeguards.
2. PLAN-010 — auto-configure exactly one selected provider.
3. PLAN-011 — install the consumer-JPA service graph and managed schema policy.
4. PLAN-012 — add stable locations, the friendly façade, and ordered batches.
5. PLAN-013 — add the independent optional observability aggregate.

PLAN-013 shares only packaging/documentation surfaces with the upload sequence; it must still be verified against the completed generic-starter dependency boundary.
