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
**Why:** Every consuming app has different ownership, authorization, lifecycle, and business fields for logical assets. dedup4j owns only reusable physical content metadata.
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
**Why:** Developers and operators need one lightweight read-only view across multiple local dedup4j instances, including historical traffic contribution and deduplication savings.
**Tradeoffs:** The dashboard only observes instances while their local management endpoints are reachable, and the MVP is not suitable for remote deployment.
**Alternatives considered:** An embedded dashboard, push-only telemetry, and direct database/object-store access were rejected for the initial version because they increase deployment coupling, delivery complexity, or provider/schema coupling.

## Generic starter bundles provider adapters

**Date:** 2026-09-03
**Why:** Upload consumers should install one standard starter and select local, S3, or Azure through configuration rather than understand internal module assembly.
**Tradeoffs:** Every starter consumer receives all provider SDKs, increasing classpath size and dependency-maintenance responsibility; convergence and vulnerability gates become release requirements.
**Alternatives considered:** Provider-specific starters and the existing base-starter-plus-adapter installation were rejected for the primary experience. See ADR-007, which supersedes ADR-004's packaging decision while preserving provider-neutral core boundaries.

## Consumer database backs dedup4j metadata

**Date:** 2026-09-03
**Why:** All application instances must share one authoritative content identity and reference count without consumers manually assembling JPA services.
**Tradeoffs:** The starter depends on the application's database/JPA health and owns versioned schema compatibility. Production schema mutation requires explicit `always`; the default auto-initializes embedded databases only.
**Alternatives considered:** A private SQLite file was deferred because it fragments metadata in multi-instance deployments and adds a second persistence lifecycle. See ADR-008.

## Spring facade owns input adaptation, not application responses

**Date:** 2026-09-03
**Why:** Common uploads should be one line while dedup4j remains the sole physical uploader and consuming applications retain their own logical records, DTOs, URLs, and presigning rules.
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
**Tradeoffs:** Public metadata operations commit independently of caller transactions; object storage remains outside the database transaction. The initial prefixed migration requires an explicit data transition for legacy installations. Consumer Liquibase configuration must remain effective alongside dedup4j's dedicated initializer.
**Alternatives considered:** Restarting resource-local transactions on a shared EntityManager is invalid. Suppressing consumer entity scans or migrations violates consumer infrastructure reuse. A service-global storage lock was rejected when forced concurrency exposed a local-filesystem publication race; that fix belongs in the adapter.

## Adopt Spring Boot 4.1 and Jackson 3

**Date:** 2026-09-06

**Why:** The project is still early enough to absorb Spring Boot 4's module and package changes before more consumers depend on the Boot 3 surface. The standalone dashboard therefore uses the Boot 4 MVC and Jackson starters and Jackson 3 APIs.

**Tradeoffs:** Boot 4 is a major upgrade; consumers and future integrations must use the Boot 4-compatible auto-configuration and JSON module layout.
**Alternatives considered:** Remaining on Spring Boot 3.5 was rejected because it would defer migration cost and leave the project on the older framework line while the public API is still forming.

## Durable append-only history replaces in-memory totals

**Date:** 2026-10-19
**Why:** In-memory counters reset on restart, differ per replica, and counted failed uploads. A database-backed, append-only ledger committed with each upload gives shared all-time totals and a gapless failure sequence that dashboards can page without gaps.
**Tradeoffs:** History runs only on PostgreSQL and H2, where append-only triggers are implemented; other databases fail startup unless `dedup4j.history.enabled=false`. Uploads serialize on the dataset row lock for a few statements at commit, negligible at thousands per day. Totals start at zero with no backfill, and mistakes are fixed only by compensating rows.
**Alternatives considered:** Keeping in-memory totals was rejected as non-durable. Mutable summary rows were rejected because they allow silent edits. Supporting every database without enforcement was rejected because append-only must be enforced in the database.

## Scheduled reconciliation reports only and runs on one designated instance

**Date:** 2026-10-26
**Why:** The application's logical counts and dedup4j's `ref_count` are read at different moments, so a transaction still in flight looks like drift. Repairing it automatically would corrupt the count, and a release to zero deletes bytes before commit (#80). Requiring a mismatch in two consecutive runs does not help, because one pending transaction can span both. Reporting is safe at any time.
**Tradeoffs:** Drift is detected on a schedule but still fixed by an explicit repair call (`CoordinatedRepair.run` since #81). Instances are not coordinated: operators enable the schedule on one instance per shared database, and the last result is held in memory per instance, not persisted. `mode` accepts only `report` so `repair` fails startup instead of being ignored.
**Alternatives considered:** Scheduled repair, including repair limited to non-zero targets, was deferred to #81, which needs the application's reference writes to take part in coordination. A home-made lease table was rejected: expiry, renewal and clock skew would all be ours to get right, and it still would not coordinate application writes. Optional ShedLock integration and persisted run history were deferred.

## Delete released objects after the release commits, revalidated under the row lock

**Date:** 2026-10-28
**Why:** Deleting the object inside the releasing transaction lost bytes whenever that transaction rolled back (#80). Because content rows stay at zero and an upload can restore them, a plain after-commit delete could also remove an object that was just written again. Deleting in a second transaction that relocks the row and checks the count is still zero closes both gaps.
**Tradeoffs:** A release now takes two transactions when it reaches zero. A cleanup failure after commit leaves an unreferenced object in storage; it is logged and recorded but not thrown, since retrying a committed release could drop a reference an upload restored. Nothing sweeps such objects yet, and a delete that times out locally but completes remotely after a restore is not guarded against without generation-aware deletes (since addressed per provider, #84). Callers of the raw service, `ReferenceCountService` or repair now run cleanup themselves.
**Alternatives considered:** Rethrowing cleanup failures was rejected because it reports a committed release as failed. Per-mismatch repair transactions were deferred to #81, since they change repair atomicity without coordinating with application writes.

## Find leftover released objects by checking storage, not a deletion marker

**Date:** 2026-10-28
**Why:** Objects left by a failed post-release delete must be found later (#83). A marker column recording that an object was deleted would go stale: an upload that rewrites the bytes of released content and then rolls back leaves the row marked deleted while the object exists again, and storage writes are not part of the database transaction. Checking `BlobStorage.exists` under the row lock asks storage itself, so a pass neither skips a leftover among the rows it visits nor deletes restored content. Rows released behind its cursor wait for the next pass.
**Tradeoffs:** Every pass makes one existence check per released row, and released rows are never removed, so the cost grows with them. The pass is a bean the application calls; it is not scheduled, so leftovers stay until something calls it. Enable it on one instance per shared database.
**Alternatives considered:** A nullable `object_deleted_at` marker was rejected for the stale-marker case above. Calling the idempotent delete for every released row on every pass was rejected because it hides how many leftovers exist. A built-in cron schedule was deferred to keep this change small.

## Guard late-applied deletes per provider: Azure by ETag, S3 risk accepted

**Date:** 2026-10-28
**Why:** The row lock fences only deletes that take effect while it is held (#84). A delete the provider applies after the client gave up, including an earlier attempt of a retried request, can land after an upload restored the content and rewrote its object under the same key. Only a condition the provider checks when it applies the delete can fence that. Azure changes the ETag on every write, even of identical bytes, so `AzureBlobStorage.delete` reads the ETag under the caller's lock and deletes with `If-Match`; a later rewrite fails the condition. A failed condition (`ConditionNotMet`; other 412s such as lease mismatches keep the generic message) is reported as a storage failure with the object kept, not refreshed and retried. Local deletes are synchronous in-process and cannot apply late on a local filesystem.
**Tradeoffs:** An Azure delete now costs a properties read first. S3 stays unguarded: the ETag of a single-part upload is the MD5 of its bytes, so `If-Match` (supported on general purpose buckets since September 2025) cannot tell the object from a same-bytes rewrite, and last-modified-time and size conditions are directory-bucket only. The residual S3 risk, unverified in practice, is accepted; on versioned buckets a late delete only adds a delete marker, so the bytes stay recoverable. A network or custom filesystem behind the local root may not keep the local guarantee. Late puts from a timed-out upload whose row never commits can still leave orphans no row-driven pass finds.
**Alternatives considered:** Deleting the S3 version ID read under the lock was rejected as the default: deleting the current version makes the previous one current, so the delete can succeed while `exists` stays true and repeated passes peel off versions; it also needs `s3:DeleteObjectVersion`, permanently removes bytes versioning was keeping, and fails under retention or legal holds. It may return as a documented opt-in. Storing a generation token on the content row was rejected because the ETag read under the lock already identifies the write to remove without a schema change.

## Repair rechecks each mismatch under the row lock, against writers that commit atomically

**Date:** 2026-10-29
**Why:** The application's counts and `ref_count` are read at different moments, so an application transaction in flight looks like drift (#81). Repair now treats discovery as a nomination only: each candidate is rechecked in its own READ COMMITTED transaction that locks the content row and then asks the application for that content's count. Applications that change the dedup4j count and their own reference in one transaction hold that lock until both commit, so repair either waits for them or sees their committed result, and never applies a half-done write. READ COMMITTED is required because the lookup must see rows committed while repair waited; a transaction manager that cannot set it stops the run before any change. Every applied repair is appended to `dedup4j_reference_repair` in the same transaction as the count change. Repairs to zero delete through the #80 path, which rechecks under the lock, so content restored first keeps its bytes.
**Tradeoffs:** Safety depends on the writer contract, which the auto-configured `BlobStore` does not meet: it commits the count in its own transaction. Until a transaction-joining write service exists (part 2 of #81), only applications that write through the raw, transaction-joining services are covered. Content at zero that is still referenced is reported and left at zero, references to unknown content are not created, and rows that drift after discovery passed them wait for the next pass. Scheduled `mode=repair` is not offered. The audit table rejects UPDATE, DELETE and TRUNCATE by trigger on PostgreSQL; H2 has no TRUNCATE trigger, so a self-referencing foreign key (`compensates_repair_id`) blocks TRUNCATE there. Repair refuses to run on other databases, where nothing would keep the audit append-only; the check happens when a run starts, so those applications still start. Applications that apply dedup4j's schema themselves must apply the new changesets before upgrading.
**Alternatives considered:** A quiescence mode in which the application drains writes before repair was rejected in favour of this lock-and-recheck model. The former `ReconciliationService.repair`, which computed deltas before locking and applied them unaudited, was removed rather than kept beside the new path. Re-reading under the lock at REPEATABLE READ was rejected: PostgreSQL would raise a serialization failure or use a snapshot from before the lock wait.
