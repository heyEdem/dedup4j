# Architectural Decisions

> ADR entries explain WHY. Detailed decision records live in `docs/adrs/`.

## Content identity includes size

**Date:** 2026-07-04  
**Why:** Byte-identical deduplication needs stable content identity across storage providers. The chosen identity is `hash_algorithm + content_hash + size_bytes`.  
**Tradeoffs:** Hashing must complete before duplicate detection can finish. Future hash algorithms require identity versioning.  
**Alternatives considered:** Hash-only identity was rejected because it drops the explicit size guard.

## Core stays provider-neutral

**Date:** 2026-07-04  
**Why:** The library must support S3, Azure Blob Storage, local filesystem storage, and future object stores without coupling business logic to any SDK.  
**Tradeoffs:** Provider modules require separate adapters and tests.  
**Alternatives considered:** A single module with all providers was rejected because it would force unused SDK dependencies on consumers.

## Applications own logical assets

**Date:** 2026-07-04  
**Why:** Every consuming app has different ownership, authorization, lifecycle, and business fields for logical assets. Blob Helper owns only reusable physical content metadata.  
**Tradeoffs:** Reconciliation requires an app-provided reference-count source.  
**Alternatives considered:** Library-owned logical asset tables were rejected as too opinionated.

## Duplicate uploads skip physical writes

**Date:** 2026-07-04  
**Why:** The main cost-saving behavior is avoiding repeated object-store writes and storage for identical bytes.  
**Tradeoffs:** Upload orchestration must handle duplicate-key races and storage/database ordering carefully.  
**Alternatives considered:** Letting storage adapters detect duplicates was rejected because reference counting is metadata behavior, not storage IO behavior.

## Reconciliation repair is opt-in

**Date:** 2026-07-04  
**Why:** Reference-count drift repair can mutate production metadata and must be explicit.  
**Tradeoffs:** Operators must choose when to repair instead of relying on automatic mutation.  
**Alternatives considered:** Scheduled repair enabled by default was rejected.

## Local dashboard uses self-registration and pull collection

**Date:** 2026-08-28
**Why:** Developers and operators need one lightweight read-only view across multiple local Blob Helper instances, including historical traffic contribution and deduplication savings.
**Tradeoffs:** The dashboard only observes instances while their local management endpoints are reachable, and the MVP is not suitable for remote deployment.
**Alternatives considered:** An embedded dashboard, push-only telemetry, and direct database/object-store access were rejected for the initial version because they increase deployment coupling, delivery complexity, or provider/schema coupling.

## Generic starter bundles provider adapters

**Date:** 2026-09-03  
**Why:** Upload consumers should install one standard starter and select local, S3, or Azure through configuration rather than understand internal module assembly.  
**Tradeoffs:** Every starter consumer receives all provider SDKs, increasing classpath size and dependency-maintenance responsibility; convergence and vulnerability gates become release requirements.  
**Alternatives considered:** Provider-specific starters and the existing base-starter-plus-adapter installation were rejected for the primary experience. See ADR-007, which supersedes ADR-004's packaging decision while preserving provider-neutral core boundaries.

## Consumer database backs Blob Helper metadata

**Date:** 2026-09-03  
**Why:** All application instances must share one authoritative content identity and reference count without consumers manually assembling JPA services.  
**Tradeoffs:** The starter depends on the application's database/JPA health and owns versioned schema compatibility. Production schema mutation requires explicit `always`; the default auto-initializes embedded databases only.  
**Alternatives considered:** A private SQLite file was deferred because it fragments metadata in multi-instance deployments and adds a second persistence lifecycle. See ADR-008.

## Spring facade owns input adaptation, not application responses

**Date:** 2026-09-03  
**Why:** Common uploads should be one line while Blob Helper remains the sole physical uploader and consuming applications retain their own logical records, DTOs, URLs, and presigning rules.  
**Tradeoffs:** The Spring-facing API depends on `MultipartFile`, and batch callers must inspect ordered per-item results because storage cannot offer relational all-or-nothing rollback.  
**Alternatives considered:** Requiring `StoreBlobCommand`, returning `void`/`Optional`, forcing `BlobReference` from controllers, and atomic batch claims were rejected. See ADR-009.

## Single-application observability has one aggregate dependency

**Date:** 2026-09-03  
**Why:** Management and the embedded dashboard belong together in the installation experience but should not enter upload-only applications.  
**Tradeoffs:** An extra published aggregate artifact is maintained, while the existing implementation modules and activation controls remain independent.  
**Alternatives considered:** Adding observability to the generic starter and merging management/dashboard source modules were rejected. The standalone fleet dashboard remains separate. See ADR-010.

## Provider defaults back off as one graph

**Date:** 2026-09-05  
**Why:** PLAN-010 implements ADR-007's configuration-driven provider wiring. An application-owned `BlobStorage` suppresses default adapter properties and cloud clients together, preventing unused defaults from demanding a bucket, container, or region. Final validation requires a supported explicit selection and exactly one storage bean by type.  
**Tradeoffs:** Custom storage still requires `local`, `s3`, or `azure` selection; it is trusted to implement the application's intended storage behavior. Spring owns S3 client cleanup, while its auto-configured storage wrapper disables inferred destruction.  
**Alternatives considered:** Backing off only the adapter leaves unnecessary client construction active; selecting by bean-name substrings rejects valid custom bean names and can miss conflicting providers.

## Spring owns metadata recovery and guarded schema initialization

**Date:** 2026-09-06  
**Why:** PLAN-011 implements ADR-008 by emitting a transaction-neutral identity-conflict signal from JPA and recovering only after Spring rolls back. The starter owns additive entity discovery, granular defaults, prefixed migrations, and validation before persistence initialization.  
**Tradeoffs:** Public metadata operations commit independently of caller transactions; object storage remains outside the database transaction. The initial prefixed migration requires an explicit data transition for legacy installations. Consumer Liquibase configuration must remain effective alongside Blob Helper's dedicated initializer.  
**Alternatives considered:** Restarting resource-local transactions on a shared EntityManager is invalid. Suppressing consumer entity scans or migrations violates consumer infrastructure reuse. A service-global storage lock was rejected when forced concurrency exposed a local-filesystem publication race; that fix belongs in the adapter.

## Adopt Spring Boot 4.1 and Jackson 3

**Date:** 2026-09-06

**Why:** The project is still early enough to absorb Spring Boot 4's module and package changes before more consumers depend on the Boot 3 surface. The standalone dashboard therefore uses the Boot 4 MVC and Jackson starters and Jackson 3 APIs.

**Tradeoffs:** Boot 4 is a major upgrade; consumers and future integrations must use the Boot 4-compatible auto-configuration and JSON module layout.
**Alternatives considered:** Remaining on Spring Boot 3.5 was rejected because it would defer migration cost and leave the project on the older framework line while the public API is still forming.
