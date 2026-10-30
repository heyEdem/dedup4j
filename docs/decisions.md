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
**Alternatives considered:** A nullable `object_deleted_at` marker was rejected for the stale-marker case above (since adopted, with the orphan sweep covering that case: see "Skip released rows whose object is confirmed deleted"). Calling the idempotent delete for every released row on every pass was rejected because it hides how many leftovers exist. A built-in cron schedule was deferred to keep this change small.

## Guard late-applied deletes per provider: Azure by ETag, S3 risk accepted

**Date:** 2026-10-28
**Why:** The row lock fences only deletes that take effect while it is held (#84). A delete the provider applies after the client gave up, including an earlier attempt of a retried request, can land after an upload restored the content and rewrote its object under the same key. Only a condition the provider checks when it applies the delete can fence that. Azure changes the ETag on every write, even of identical bytes, so `AzureBlobStorage.delete` reads the ETag under the caller's lock and deletes with `If-Match`; a later rewrite fails the condition. A failed condition (`ConditionNotMet`; other 412s such as lease mismatches keep the generic message) is reported as a storage failure with the object kept, not refreshed and retried. Local deletes are synchronous in-process and cannot apply late on a local filesystem.
**Tradeoffs:** An Azure delete now costs a properties read first. S3 stays unguarded: the ETag of a single-part upload is the MD5 of its bytes, so `If-Match` (supported on general purpose buckets since September 2025) cannot tell the object from a same-bytes rewrite, and last-modified-time and size conditions are directory-bucket only. The residual S3 risk, unverified in practice, is accepted; on versioned buckets a late delete only adds a delete marker, so the bytes stay recoverable. A network or custom filesystem behind the local root may not keep the local guarantee. Late puts from a timed-out upload whose row never commits can still leave orphans no row-driven pass finds.
**Alternatives considered:** Deleting the S3 version ID read under the lock was rejected as the default: deleting the current version makes the previous one current, so the delete can succeed while `exists` stays true and repeated passes peel off versions; it also needs `s3:DeleteObjectVersion`, permanently removes bytes versioning was keeping, and fails under retention or legal holds. It may return as a documented opt-in. Storing a generation token on the content row was rejected because the ETag read under the lock already identifies the write to remove without a schema change.

## Repair rechecks each mismatch under the row lock, against writers that commit atomically

**Date:** 2026-10-29
**Why:** The application's counts and `ref_count` are read at different moments, so an application transaction in flight looks like drift (#81). Repair now treats discovery as a nomination only: each candidate is rechecked in its own READ COMMITTED transaction that locks the content row and then asks the application for that content's count. Applications that change the dedup4j count and their own reference in one transaction hold that lock until both commit, so repair either waits for them or sees their committed result, and never applies a half-done write. READ COMMITTED is required because the lookup must see rows committed while repair waited; a transaction manager that cannot set it stops the run before any change. Every applied repair is appended to `dedup4j_reference_repair` in the same transaction as the count change. Repairs to zero delete through the #80 path, which rechecks under the lock, so content restored first keeps its bytes.
**Tradeoffs:** Safety depends on the writer contract, which the auto-configured `BlobStore` does not meet: it commits the count in its own transaction. Applications meet it through `TransactionalBlobReferences` (part 2 of #81, next entry) or the raw, transaction-joining services. Content at zero that is still referenced is reported and left at zero, references to unknown content are not created, and rows that drift after discovery passed them wait for the next pass. Scheduled `mode=repair` is not offered. The audit table rejects UPDATE, DELETE and TRUNCATE by trigger on PostgreSQL; H2 has no TRUNCATE trigger, so a self-referencing foreign key (`compensates_repair_id`) blocks TRUNCATE there. Repair refuses to run on other databases, where nothing would keep the audit append-only; the check happens when a run starts, so those applications still start. Applications that apply dedup4j's schema themselves must apply the new changesets before upgrading.
**Alternatives considered:** A quiescence mode in which the application drains writes before repair was rejected in favour of this lock-and-recheck model. The former `ReconciliationService.repair`, which computed deltas before locking and applied them unaudited, was removed rather than kept beside the new path. Re-reading under the lock at REPEATABLE READ was rejected: PostgreSQL would raise a serialization failure or use a snapshot from before the lock wait.

## Joined writes report lost insert races for the caller to retry

**Date:** 2026-10-29
**Why:** `CoordinatedRepair` is only correct when the count change and the application's reference commit in one transaction (#81 part 2), and the auto-configured services each open their own. `TransactionalBlobReferences` joins the caller's transaction instead. Two uploads of the same new bytes can race on the content's unique identity; PostgreSQL aborts the loser's whole transaction, so the service cannot retry inside it as the `REQUIRES_NEW` service does. It throws `ContentIdentityConflictException`, a Spring `ConcurrencyFailureException`, and the caller reruns its whole transaction, which then finds and retains the winner. Pending caller changes are flushed before the content insert so their constraint failures are not mistaken for this race. The upload ledger row is appended in a before-commit callback, after a flush, so the shared dataset lock is held only through commit, not through the caller's whole transaction.
**Tradeoffs:** Every application using joined uploads must handle the retry with its input reopened. Content locks are held until the caller commits, so long transactions delay repair and other writers of the same content, and transactions touching several contents can deadlock unless they take them in one order. A caller that rolls back after new content was written leaves an object no row points to; nothing finds it yet (#89). Application before-commit callbacks that take further locks must run before the history append, or they can deadlock with another upload's append; this is documented, not enforced. Persistent history needs the caller's transaction at READ COMMITTED; an explicit other level is refused, while the database default is accepted as elsewhere. No batch upload is offered, because one failed item aborts the shared transaction on PostgreSQL.
**Alternatives considered:** Creating the row at zero references in a separate short transaction and claiming it in the caller's was rejected: it needs a second connection while the caller holds one, can wait on the suspended caller's locks, and leaves committed rows behind on rollback. A native `INSERT ... ON CONFLICT DO NOTHING` was rejected as database-specific; it may return as a dialect option. A savepoint around the insert was rejected because the Hibernate persistence context is unreliable after rolling back to one. Deleting the object at rollback was rejected because another writer may own the same deterministic key.

## Schedule coordinated repair as an explicit reconciliation mode

**Date:** 2026-10-30
**Why:** Scheduled reconciliation shipped report-only because a repair could not tell drift from application writes still in flight. #81 closed that gap: `CoordinatedRepair` rechecks each mismatch under the row lock, and `TransactionalBlobReferences` lets applications commit the count and their reference together. `dedup4j.reconciliation.mode=repair` now runs `CoordinatedRepair` on the existing schedule, reusing its lifecycle, locking and shutdown. Choosing the mode is the opt-in; it is only correct when every writer on every instance, cascades included, follows the writer contract.
**Tradeoffs:** Startup fails without a `ContentReferenceCountSource`, without a `CoordinatedRepair` bean, or on a database other than PostgreSQL or H2; neither startup nor a run verifies that the audit triggers are installed, and the transaction manager's isolation support is only checked when a run begins its first repair transaction. The scheduled repair uses the application's `CoordinatedRepair` bean, so overrides apply, but its EntityManager must be transaction-bound (Spring Boot's shared one is); the report path keeps its own private one. Drift gauges stay those of the last report run, because a repair pass does not measure what remains. A repair run succeeds when the pass completes even if rows failed, which its result counts. Stopping interrupts between rows; a row already inside its transaction commits or rolls back on its own. `ReconciliationRun` gains `mode` and `repair` components, changing its public constructor; the management JSON change is additive.
**Alternatives considered:** A separate acknowledgement property was rejected as redundant with choosing the mode. Recording remaining drift (released plus failed rows) in the gauges was rejected because failed candidates may already be consistent and later drift is not discovered. Building a private `CoordinatedRepair` for the schedule was rejected so application overrides keep applying.

## Sweep orphaned objects by adopting them as released rows, on a library-owned schedule

**Date:** 2026-10-30
**Why:** Uploads write the object before inserting its row, so a rolled-back joined upload, or a put applied after it timed out, leaves an object no row points to (#89). Deleting "objects without a row" directly is unsafe: a row cannot be locked before it exists, and an upload can write the same deterministic key and commit its row between the check and the delete, leaving a dangling row. The sweep therefore adopts each orphan as a released row (zero references) in its own transaction and deletes the object through the existing locked `cleanUpReleased`. The unique identity constraint makes the adoption insert wait for, and lose to, an upload committing the same content; an upload arriving after adoption finds the released row and restores the object under its lock. The sweep only adopts objects whose bytes hash, with the configured hasher, to the identity whose key the configured strategy gives, so it never adopts foreign or corrupt objects; that also keeps the algorithm spelling the same as uploads use. Edem chose that the library run this itself: `ScheduledCleanup` schedules the released object pass by default and the orphan sweep with one property, `dedup4j.cleanup.orphans.enabled=true`. The sweep is opt-in because an instance pointed at the wrong database (an empty schema, an older snapshot) would see every real object as orphaned and delete the whole prefix within a day; a release cannot do that.
**Tradeoffs:** Adopted rows are never removed, like other released rows, until an upload restores them, so the table grows; the management and embedded dashboard totals now count only referenced rows. The released pass, now automatic, checks every released row each night with one `exists` call, so its cost grows with them. Adopted rows keep the content type storage reports; their original extension is lost. Each orphan candidate is downloaded once to hash it. Enabling the sweep requires a key prefix without a trailing slash that belongs to this database alone (a shared bucket is already unsafe because releases delete shared keys), or startup fails. A failed sweep does not stop the released pass. A joined upload racing an adoption gets `ContentIdentityConflictException` and retries; the 24-hour grace period makes that rare but correctness does not depend on it. Objects under an old key prefix, or an extra copy of an identity whose row points elsewhere, are not reclaimed. A put applied after the sweep deleted its object is removed by a later released pass. Every instance runs the schedule; deletions are safe under the row lock, but listing and reads repeat per instance.
**Alternatives considered:** Deleting directly after a row check, with only a grace period, was rejected as racy. Parsing the identity from the key was rejected because the key strategy is replaceable and keys lower-case the algorithm. A default `list` method on `BlobStorage` was rejected for an optional `ListableBlobStorage`, so custom storage compiles unchanged and the sweep is enabled by capability. Leaving the sweep for the application to call, like `ReleasedObjectCleanup` before, was rejected at Edem's request.

## Skip released rows whose object is confirmed deleted

**Date:** 2026-10-30
**Why:** Since #89 the released object pass runs every night and checks every released row with one storage `exists` call; released rows are never removed, so its cost grew without bound (#92). A nullable `object_deleted_at` column is now set under the row lock when the object is deleted or found absent, and the pass skips marked rows. Every path that references the content again goes through `AssetContent.incrementRefCount`, which clears the mark. This reverses "Find leftover released objects by checking storage, not a deletion marker": the stale mark it feared (a restore that writes the object and then rolls back, or a put applied late) is real, so the orphan sweep now deletes an object whose only row is released and marked, through the same locked `cleanUpReleased`.
**Tradeoffs:** Without `dedup4j.cleanup.orphans.enabled=true`, an object written back behind a marked row stays in storage; before, the nightly released pass would have removed it. A key that several rows point to is kept, but that does not guard against a second row committed during the pass; like release, which deletes a key without checking other rows, this relies on `ObjectKeyStrategy` giving distinct identities distinct keys, now stated in its contract. Existing released rows are checked once after upgrading, then marked.
**Alternatives considered:** Deleting released rows after a retention period was rejected: an upload holding the row id would hit not-found, and other tables reference content ids. A rollback hook that cleans up after a failed restore was rejected because a crash or a late put bypasses it, so the sweep would still be needed. Rechecking old marked rows on a slower cadence only spreads the same growing cost.
