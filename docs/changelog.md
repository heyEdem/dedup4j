# Changelog

## 2026-10-30 — Scheduled coordinated repair (`dedup4j.reconciliation.mode=repair`)

- `ReconciliationMode.REPAIR`: the scheduled reconciliation runs `CoordinatedRepair` instead of the read-only report. Startup fails without one `ContentReferenceCountSource`, without a `CoordinatedRepair` bean, or on a database other than PostgreSQL or H2. Only correct when every writer follows the writer contract.
- `ScheduledReconciliation` has a repair constructor and `mode()`; in repair mode `runNow` refuses an ambient transaction before waiting for a running pass, and drift gauges keep the last report's counts.
- `CoordinatedRepair.requireSupportedDatabase()` is public; an interrupt that makes a row fail, or arrives during the last row, now ends the pass as cancelled.
- **Breaking:** `ReconciliationRun` gains `mode` and `repair` components (public constructor changed); the management `/reconciliation` JSON adds `mode` and `repair`.
- Modules affected: `dedup4j-spring-boot-starter`, `dedup4j-spring-boot-management` (tests).

## 2026-10-29 — Transaction-joining write service for the repair writer contract (#81, part 2)

- New `TransactionalBlobReferences` bean: `store`, `retain` and `release` join the caller's transaction, so an application's reference row and the dedup4j count change commit together, as `CoordinatedRepair` requires. Calls outside a read-write transaction are rejected; with persistent history, an explicit isolation other than READ COMMITTED is refused.
- A lost insert race throws the new `ContentIdentityConflictException` (a Spring `ConcurrencyFailureException`); the caller retries its whole transaction. Upload history is appended in a before-commit callback (an upload made during the commit is appended afterwards in a new transaction); meters, failure recording and the delete of released content run on completion, so an earlier failing application callback cannot skip them. No batch method.
- `AssetContentMutationService` flushes pending changes before inserting new content, so a caller's own constraint failure is not reported as an identity race.
- A rollback after new content was written leaves an orphaned object; tracked in #89.
- Modules affected: `dedup4j-jpa`, `dedup4j-spring-boot-starter`.

## 2026-10-29 — Coordinated reference-count repair with an append-only audit (#81, part 1)

- New `CoordinatedRepair` bean: `run(LogicalReferenceCountSource, ContentReferenceCountSource)` nominates mismatches, then per content locks the row at READ COMMITTED, reads the application's count under the lock and repairs only a real difference. It must be called outside a transaction, runs only on PostgreSQL or H2, and stops if the transaction manager cannot set READ COMMITTED. Repairs to zero delete the object after commit through the lock-revalidated delete.
- New append-only `dedup4j_reference_repair` table (main changelog `002`–`004`), immutable `ReferenceRepair` entity, insert-only `ReferenceRepairRepository` and `ReferenceRepairService` in `dedup4j-jpa`. PostgreSQL triggers reject UPDATE, DELETE and TRUNCATE; H2 triggers reject UPDATE and DELETE and a self-referencing foreign key blocks TRUNCATE. The schema validator now requires the table: applications applying dedup4j's schema themselves must apply the new changesets.
- **Breaking:** `ReconciliationService.repair`, its repair-enabled constructors and `ReconciliationReport.releasedContentIds` are removed; `ReconciliationService` is read-only.
- Writer contract documented on `ContentReferenceCountSource`: the count change and the application's reference commit in one transaction. The auto-configured `BlobStore` does not meet it yet; a transaction-joining write service is part 2.
- Modules affected: `dedup4j-jpa`, `dedup4j-spring-boot-starter`.

## 2026-10-28 — Split CI into build, unit-test and PostgreSQL-test stages

- `ci.yml` now runs a `build` job (Enforcer, compile, package, no tests), then `unit-tests` and `postgres-tests` in parallel, each uploading its own Surefire reports. Superseded PR runs are cancelled; pushes to branches always finish.
- The three Testcontainers test classes in the starter carry `@Tag("postgres")`; the stages select them with `-Dgroups` / `-DexcludedGroups`. Together they cover the full suite (410 + 25 tests).
- Modules affected: CI workflow, `dedup4j-spring-boot-starter` tests.

## 2026-10-28 — Guard deletes that the provider applies late (#84)

- `AzureBlobStorage.delete` reads the blob's ETag and deletes with `If-Match`, so a delete Azure applies after the client gave up cannot remove an object an upload rewrote in the meantime. A rewrite between the read and the delete fails the call with `BlobStorageException` and the object kept; released-object cleanup retries it.
- S3 and local deletes are unchanged. S3 cannot guard same-bytes rewrites on general purpose buckets, so the late-delete risk there is accepted and documented; local deletes are synchronous. `BlobStorage.delete` documents the contract.
- Modules affected: `dedup4j-core` (javadoc only), `dedup4j-storage-azure`, `dedup4j-storage-s3` and `dedup4j-storage-local` (javadoc only).

## 2026-10-28 — Clean up objects left in storage by released content (#83)

- New `ReleasedObjectCleanup` bean. Its `run()` pages through content at zero references and, per row in its own transaction, relocks it and deletes its object only while the count is still zero and the object exists. Restored content keeps its object; failures are logged (`event=blob.cleanup.failed`), recorded in failure history and retried next pass. The application decides when to call it; nothing schedules it.
- `ReleasedCleanupResult` reports checked, deleted, already absent, referenced and failed rows. New metrics `dedup4j.cleanup.deleted` (counter, incremented as each delete commits, so an interrupted pass keeps its count) and `dedup4j.cleanup.remaining` (gauge, failed rows of the last completed pass).
- `ReferenceCountService.cleanUpReleased` returns `ReleasedCleanupOutcome`; `AssetContentRepository.findReleasedPageAfter` pages released rows. No schema change.
- Modules affected: `dedup4j-jpa`, `dedup4j-spring-boot-starter`.

## 2026-10-28 — Released content is deleted only after the release commits (#80)

- `ReferenceCountService.release` returns whether the count reached zero and no longer deletes. New `deleteIfReleased` deletes in a later transaction under the row lock, only while the count is still zero, so content restored by an upload in between keeps its object.
- `SpringTransactionalBlobDeduplicationService.release` commits the decrement, then cleans up in its own transaction. Cleanup failures are logged (`event=blob.cleanup.failed`) and recorded in failure history instead of thrown; the object stays in storage under a zero count.
- `DefaultBlobDeduplicationService` gains `deleteReleased` for callers owning their transactions; `get` rejects released content.
- `ReconciliationService.repair` no longer deletes; `ReconciliationReport.releasedContentIds` lists content it took to zero.
- Modules affected: `dedup4j-jpa`, `dedup4j-spring-boot-starter`.

## 2026-10-26 — Scheduled reference-count reconciliation (report-only)

- New `dedup4j.reconciliation.*` properties: `enabled` (`false`), `cron` (`0 0 3 * * *`), `zone`, `mode` (`report` only) and `sample-size` (`100`). Startup fails when enabled without one `LogicalReferenceCountSource` (or one marked `@Primary`), or with an invalid cron, zone, mode or sample size.
- `ScheduledReconciliation` runs `ReconciliationService.summarize` on a private cron scheduler. Each run pages through content by ID, counts mismatches and dangling references, keeps bounded samples, and never writes. Failed runs are logged and kept as the last result without stopping the schedule. Runs read through their own shared `EntityManager` built from the `EntityManagerFactory`, never the application's `EntityManager`, and shutdown interrupts an active scheduled or manual run and waits up to 30 seconds in total.
- Metrics `dedup4j.reconciliation.runs` (timer by outcome), `dedup4j.reconciliation.mismatches` and `dedup4j.reconciliation.dangling`; management `GET /v1/reconciliation` returns this instance's schedule and last run.
- `AssetContentRepository.findPageAfter` adds keyset paging.
- Docs: removed the documented but never implemented `dedup4j.cleanup.reconciliation-enabled` and `dedup4j.cleanup.delete-physical-on-zero-references`; physical deletion on the last release is always on. ADR added for report-only, single-instance scheduling.
- Modules affected: `dedup4j-jpa`, `dedup4j-spring-boot-starter`, `dedup4j-spring-boot-management`.

## 2026-10-24 — Oversized BlobStore uploads recorded in failure history

- `DefaultBlobStore` no longer checks `dedup4j.deduplication.max-upload-size` itself. The service already enforces it, so an oversized upload through `BlobStore` is now rejected inside the auto-configured service and recorded in upload failure history, like content-type and other validation failures. The message and exception are unchanged (`BlobValidationException: Upload size N exceeds maximum M`), the stream is still closed, nothing is stored, and batches continue past the item.
- Behaviour change for applications with their own `BlobDeduplicationService`: `BlobStore` no longer enforces the size limit for them; the custom service must.
- `DefaultBlobStore(BlobDeduplicationService)` is the new constructor. The `(service, Dedup4jProperties)` constructor is deprecated for removal and ignores the properties.
- Docs: `docs/implementation.md` now names the facade files `BlobStore`, `DefaultBlobStore` and their tests.
- Modules affected: `dedup4j-spring-boot-starter`.

## 2026-10-23 — Allowed upload content types

- New `dedup4j.deduplication.allowed-content-types` (`type/subtype` or `type/*`, comma-separated). Empty by default, which allows every upload as in 0.1.0. When set, `DefaultBlobDeduplicationService` rejects an upload whose declared type is missing, malformed or not listed with `BlobValidationException` before reading its stream, so nothing is written to storage or content metadata and a rejected duplicate does not gain a reference. The auto-configured service records the rejection in upload history; batch uploads continue past it. Only the declared type is checked; the stored type is kept as declared.
- `ContentTypePolicy` is registered by `Dedup4jAutoConfiguration`, outside the persistence condition, so a malformed entry fails startup even when the application supplies its own `BlobDeduplicationService` or has no JPA setup. Control and non-ASCII characters are rejected rather than normalized into a match. A custom service only enforces the list if it uses the policy.
- `DefaultBlobDeduplicationService` gains a constructor that takes a `ContentTypePolicy`; the existing constructors allow all types.
- Docs: replaced the never-implemented `strict-content-type-validation` property with the real one, and corrected the README and architecture map to name the `BlobStore` facade.
- Modules affected: `dedup4j-spring-boot-starter`.

## 2026-10-22 — Lost insert race no longer fails when the winner was released

- Fix: when two uploads of new content race and the loser's insert hits the unique constraint, the retry now succeeds even if the winning content was released to zero (and its object deleted) before the retry. The retry writes the object back under the row lock and restores the count, recorded as a physical write. Previously it failed with `ContentNotFoundException`.
- The auto-configured service now reads the upload before opening its transaction, so a slow upload no longer holds a database transaction open, and a lost race is retried with the same bytes. Size validation and failure recording are unchanged. Custom `BlobDeduplicationService` delegates keep the previous retry, which only retains the winner.
- Modules affected: `dedup4j-spring-boot-starter`.

## 2026-10-22 — Upload size limit enforced in the service

- `dedup4j.deduplication.max-upload-size` is now enforced by `DefaultBlobDeduplicationService`, so a direct `BlobDeduplicationService.store` call is limited too, not only the `BlobStore` facade. A declared size over the limit is rejected before the stream is read; a stream longer than the limit is rejected after reading one byte past it, instead of being buffered whole. Rejections throw `BlobValidationException` and nothing is written to storage or to the content metadata. Through the auto-configured service, the rejection is recorded in the upload history like any other failed upload; the `BlobStore` facade still rejects an oversized declared size before calling the service, so that case is not recorded.
- `DefaultBlobDeduplicationService` gains constructors that take `maxUploadSize`. The existing constructors keep working and default to 25 MiB, the same as the property.
- Modules affected: `dedup4j-spring-boot-starter`, and project documentation.

## 2026-10-20 — 0.1.0, first Maven Central release

- First public release, under groupId `io.github.heyedem`. Java packages stay `com.edem.dedup4j.*`.
- Published: the `dedup4j` parent POM plus `dedup4j-core`, `dedup4j-jpa`, `dedup4j-storage-local`, `dedup4j-storage-s3`, `dedup4j-storage-azure`, `dedup4j-spring-boot-starter`, `dedup4j-spring-boot-management`, `dedup4j-spring-boot-dashboard` and `dedup4j-spring-boot-observability`, each with sources and Javadoc JARs and signed with key `1E7D14C37ABE8D6E5EDD0B05D0A31632E4C94CE3`.
- Not published to Central: the standalone `dedup4j-dashboard` application, which ships on GitHub Releases.
- Requires Java 21 or later; built and tested against Spring Boot 4.1.1. Licensed under Apache License 2.0.
- Includes everything listed below, notably the persistent append-only upload history and its behaviour changes (2026-10-19).
- Known limitation: the Azure adapter does not configure a managed identity or any other credential. Use `connection-string`, an `endpoint` carrying a SAS token, or your own `BlobContainerClient` bean. `dedup4j.storage.azure.account-name` is accepted but not used.
- Modules affected: all published modules (coordinates and release metadata), `README.md`, and project documentation.

## 2026-10-19 — Persistent append-only upload history

- Added durable, append-only upload and failure history (`dedup4j_history_dataset`, `dedup4j_upload_event`, `dedup4j_failure_event`) in the `dedup4j-jpa` `historystore` package and the starter `history` package, with PostgreSQL and H2 changesets at `classpath:db/dedup4j/history/db.changelog-history.yaml`. Enabled by default (`dedup4j.history.enabled`); PostgreSQL and H2 only, other databases fail startup unless disabled. Totals start at zero with no backfill.
- Behavior changes: totals are all-time and count only completed uploads (failed uploads are no longer counted). Micrometer upload meters now increment only after commit, and `dedup4j.skipped.physical.writes` counts uploads without a storage write. The legacy 4-argument `SpringTransactionalBlobDeduplicationService` constructor and a bare `DefaultBlobDeduplicationService` no longer emit upload meters.
- Management: `/metrics` adds `datasetId`, `totalsScope`, `physicalWrites`; `/info` adds `datasetId`; `/failures` gains `afterSequence` and `limit` (ascending, default 100, max 500).
- Dashboards: embedded overview field `physicalUploads` renamed `physicalWrites`. Standalone Overview `physicalUploads` now means physical writes (previously summed skipped writes). "Bytes avoided" relabelled "Storage avoided". The standalone dashboard counts each dataset once and ingests failures per dataset via `failure_cursor` (10 pages of 100 per cycle); older instances without `datasetId` are their own dataset with best-effort `since` ingestion. SQLite failure rows have origin `application` or `poll`, both kept 7 days.
- Upgrading: with `initialize-schema` `embedded` (the default) on PostgreSQL or an H2 file/server database, or with `never`, the history changelog is not applied automatically. Apply `classpath:db/dedup4j/history/db.changelog-history.yaml` (or set `always`) or startup fails naming that path. Upgrade the standalone dashboard before or together with the applications: an older dashboard multiplies trends by replica count and re-adds all-time totals as a delta after each restart.
- Review fixes: the embedded dashboard reads failures from the upload history when management is disabled; the standalone overview and instance rows expose `totalsScope` (`all-time`, `since-start`, `mixed`) with a caption; a database error while ingesting a failure page no longer advances the cursor (the page is retried); history timestamps are `TIMESTAMP WITH TIME ZONE`; `Dedup4jManagementSnapshot.Failure` keeps its 4-argument constructor.
- Isolation: history appends (the store transaction and the failure append) request READ COMMITTED when the transaction manager supports custom isolation, so they stay gapless when the database defaults to REPEATABLE READ. A manager that rejects it (`JtaTransactionManager` without `allowCustomIsolationLevels`, `JpaTransactionManager` with a plain `DefaultJpaDialect`) logs one `event=history.isolation.unsupported` WARN and appends at the database's default isolation from then on. Retain, release, get and location, and deployments with history disabled, never request a custom isolation level.
- Instance id: with no instance id configured, history rows now use the same per-start random id as the management API (`DefaultInstanceId`; previously the host name). Set `dedup4j.management.instance-id` for an id that is stable across restarts.
- The unreleased history changeset changed during development. Development databases that already ran an earlier version of it must be recreated.
- Fix: the history changelog now uses its own tracking tables (`DEDUP4J_HISTORY_DATABASE_CHANGELOG` and `_LOCK`) instead of sharing `DEDUP4J_DATABASE_CHANGELOG`, which failed startup on H2 with `DATABASE_TO_LOWER=TRUE`.
- Modules affected: `dedup4j-jpa`, `dedup4j-spring-boot-starter`, `dedup4j-spring-boot-management`, `dedup4j-spring-boot-dashboard`, `dedup4j-dashboard`, and project documentation.

## 2026-09-07 — Reconcile feature branch with Spring Boot 4 main

- Resolved the documentation conflicts while preserving the Boot 4.1.1/Jackson 3 migration and the PLAN-011/012/013 implementation history.
- Updated persistence auto-configuration to Boot 4 package locations and public Liquibase configuration APIs, and kept invalid provider selection errors ahead of service construction.
- Updated Spring Boot 4 test fixtures for relocated entity scanning and Spring Framework 7 transaction status APIs.
- Modules affected: `dedup4j-spring-boot-starter`, root build metadata, and project documentation.

## 2026-09-07 — Add combined observability starter

- Added the dependency-only `dedup4j-spring-boot-observability` aggregate for the embedded management and current-application dashboard modules.
- Added classpath, activation, disablement, servlet, and read-only route coverage while keeping the generic upload starter and standalone fleet dashboard separate.
- Documented the one-dependency embedded setup and the explicit `dedup4j.management.enabled=true` choice.
- Modules affected: root Maven reactor, `dedup4j-spring-boot-observability`, `README.md`, and project documentation.

## 2026-09-07 — Add friendly upload facade, stable locations, and ordered batches

- Added provider-neutral `BlobLocation` values and complete locations on new and duplicate `BlobReference` results, plus metadata-only location lookup through the transactional service.
- Added the Spring-facing `Dedup4j` facade for multipart, path, byte-array, and described-stream uploads with size enforcement, and auto-configured it with application back-off.
- Added ordered success/failure batch outcomes with continue-on-failure semantics and MVC usage coverage without introducing a library controller.
- Modules affected: `dedup4j-core`, `dedup4j-jpa`, `dedup4j-spring-boot-starter`, and project documentation.

## 2026-09-07 — Consumer JPA and guarded schema auto-configuration

- Implemented PLAN-011 using Luna workers: consumer entity discovery, independently replaceable defaults, prefixed Liquibase metadata migrations, guarded initialization modes, and actionable schema validation before JPA startup.
- Moved duplicate-key recovery across Spring `REQUIRES_NEW` transactions without replaying upload input or storage writes; retained framework-neutral JPA behavior and application-managed migration configuration.
- Fixed local concurrent publication through same-directory temporary files and atomic replacement; added failed-stream preservation and maximum-filename coverage. Corrected the S3 client test proxy for Spring persistence lifecycle callbacks.
- Updated consumer guidance, architecture/implementation indexes, patterns, decisions, and completion records. Legacy table data requires a separate application-managed transition.
- Modules affected: `dedup4j-jpa`, `dedup4j-spring-boot-starter`, and `dedup4j-storage-local`. Clean full-reactor verification passed all 168 tests across ten modules; no failures, errors, or skips.

## 2026-09-05 — Auto-configure selected storage providers

- Implemented PLAN-010 with nested local/S3/Azure settings, selected-provider client and storage defaults, application-client reuse, and complete back-off for application storage.
- Required explicit provider selection and exactly one storage bean by type; Spring owns S3 client shutdown, and Azure exposes its existing client factory for shared construction.
- Added provider context, client lifecycle, and automatic-discovery coverage. Full reactor verification passed: 137 tests, zero failures, errors, or skips.
- Modules affected: `dedup4j-spring-boot-starter`, `dedup4j-storage-azure`, and consumer/project documentation.

## 2026-09-01 — Fix embedded dashboard root asset paths

- Redirected the no-trailing-slash dashboard URL to its slash form so relative CSS and JavaScript assets resolve correctly.
- Modules affected: `dedup4j-spring-boot-dashboard`.

## 2026-09-01 — Add embedded dashboard starter

- Added the optional `dedup4j-spring-boot-dashboard` module with default-on, read-only current-process API and packaged responsive UI at `/dedup4j/dashboard`.
- Preserved the standalone `dedup4j-dashboard` application for multi-instance SQLite-backed fleet monitoring.
- Modules affected: root reactor, `dedup4j-spring-boot-dashboard`, and project documentation.

## 2026-08-31 — Verify local multi-instance monitoring

- Added management response contract coverage and a two-instance in-process end-to-end test for registration, polling, aggregate savings, failure isolation, and seven-day retention cleanup.
- Updated local-only startup, verification, and completion documentation for Epic 7.
- Modules affected: `dedup4j-spring-boot-management`, `dedup4j-dashboard`, and project documentation.

## 2026-08-31 — Add dashboard API and static console

- Added read-only overview, instance status, history, and seven-day failure endpoints.
- Added responsive vanilla HTML/CSS/JavaScript console with light/dark themes, trend visualization, and explicit loading, empty, disconnected, and error states.
- Modules affected: `dedup4j-dashboard` and Epic 7 planning/status documentation.

## 2026-08-31 — Persist dashboard polling history

- Added SQLite-backed instance registrations, interval metric snapshots, persisted status, and failure events with indexed instance/time queries.
- Added scheduled multi-instance polling with reset-safe cumulative counter deltas and seven-day failure-only retention cleanup.
- Modules affected: `dedup4j-dashboard` and Epic 7 planning/status documentation.

## 2026-08-31 — Add self-registration and dashboard shell

- Added the standalone executable `dedup4j-dashboard` module with loopback/9090 defaults and local registration endpoint.
- Added asynchronous management-side self-registration with YAML configuration, stable generated instance IDs, and outage isolation.
- Modules affected: `dedup4j-dashboard`, `dedup4j-spring-boot-management`, root Maven reactor, and Epic 7 planning/status documentation.

## 2026-08-31 — Add local read-only management API

- Added the optional `dedup4j-spring-boot-management` module with disabled-by-default Spring Boot auto-configuration.
- Added provider-neutral info, health, metrics, and recent-failure response contracts with GET-only endpoints.
- Modules affected: `dedup4j-spring-boot-management`, root Maven reactor, and Epic 7 planning/status documentation.

## 2026-08-30 — Add structured operational logging

- Added structured upload decision logs for new and duplicate content with content ID, provider, object key, decision, size, and an explicit short hash prefix.
- Added failed physical-delete logs with reconciliation context and SLF4J/Logback-backed coverage.
- Completed Epic 6 (5/5); project scope is now 30/35 tasks complete.
- Modules affected: `dedup4j-spring-boot-starter` and Epic 6 planning/status documentation.

## 2026-08-29 — Implement opt-in reconciliation repair

- Added an explicit, disabled-by-default repair operation that converges drift through the lock-aware JPA reference-count boundary.
- Added coverage for enabled repairs, disabled no-mutation behavior, and final-reference physical deletion.

## 2026-08-29 — Implement reconciliation mismatch reporting

- Added a read-only reconciliation service that compares stored reference counts with application-provided logical counts and reports omitted IDs as zero expected references.
- Added a repository query for loading all physical content metadata and Hibernate/H2 integration coverage proving mismatch details and no database mutation.
- Modules affected: `dedup4j-jpa`, `dedup4j-spring-boot-starter`, and Epic 6 planning/status documentation.

## 2026-08-28 — Add reconciliation contracts

- Added an application-owned logical reference count callback plus immutable validated reconciliation report and mismatch records.
- Kept report generation independent from repair commands and consuming-application schema details.
- Modules affected: `dedup4j-spring-boot-starter` and Epic 6 planning/status documentation.

## 2026-08-28 — Define local multi-instance dashboard

- Added the approved design and ADR for a fully local, read-only, pull-based dashboard with YAML self-registration.
- Added Epic 7 and PLAN-007 for the optional management module, standalone dashboard, SQLite aggregate history, light/dark UI, and seven-day failure retention.
- Updated the specification, architecture, implementation index, five-question requirements, patterns, README, and testing guidance.
- Modules affected: planned `dedup4j-spring-boot-management`, planned `dedup4j-dashboard`, and project documentation.

## 2026-08-27 — Add provider SDK boundary checks

- Added `ProviderDependencyBoundaryTest` to verify AWS and Azure SDK ownership remains isolated to their provider modules.
- Added `docs/provider-testing.md` documenting credential-free default tests and opt-in external provider test execution.
- Epic 5 is complete (5/5); the project task index is 25/30 complete.
- Modules affected: `dedup4j-core`, root Maven test configuration, provider-testing documentation, and Epic 5 planning/status documentation.

## 2026-08-27 — Implement Azure BlobStorage adapter

- Added streaming Azure Blob Storage put/get, idempotent delete, existence checks, and core exception mapping.
- Added credential-free SDK-backed contract tests using an in-process HTTP fake.
- Modules affected: `dedup4j-storage-azure` and Epic 5 planning/status documentation.

## 2026-08-27 — Add Azure storage module

- Added `dedup4j-storage-azure` to the Maven reactor with a module-local Azure SDK BOM and `azure-storage-blob` dependency.
- Added `AzureBlobStorageProperties` for container, connection string, endpoint, and account name configuration without Azure SDK types.
- Added credential-free properties coverage and verified the focused module test, Azure dependency tree, and full reactor build.
- Modules affected: root reactor, `dedup4j-storage-azure`, and Epic 5 planning/status documentation.

## 2026-08-26 — Implement S3 BlobStorage adapter

- Added streaming S3 put/get, idempotent delete, and head-based exists behavior with bucket and object metadata mapping.
- Mapped S3 404 responses to core not-found semantics and other provider failures to `BlobStorageException`; added credential-free contract tests because no external S3-compatible target is configured in the repository.
- Modules affected: `dedup4j-storage-s3` and Epic 5 planning/status documentation.

## 2026-08-26 — Add S3 storage module

- Added `dedup4j-storage-s3` to the Maven reactor with a module-local AWS SDK for Java 2.x BOM and S3 dependency.
- Added configurable S3 properties for bucket, region, optional endpoint override, and path-style access, which defaults to disabled.
- Verified AWS SDK isolation from `dedup4j-core` and `dedup4j-spring-boot-starter`; Epic 5 is in progress (1/5).
- Modules affected: root reactor, `dedup4j-storage-s3`, and Epic 5 planning/status documentation.

## 2026-08-26 — Add local storage service integration tests

- Added `LocalStorageDeduplicationIntegrationTest` covering the real service, JPA metadata, and temporary-directory local storage flow.
- Verified service readback, duplicate uploads retaining one physical file, and physical deletion after the final reference release.
- Added `dedup4j-storage-local` as a test-scoped starter dependency; Epic 4 is complete (4/4).
- Modules affected: `dedup4j-spring-boot-starter`, `dedup4j-storage-local`, and Epic 4 planning/status documentation.

## 2026-08-26 — Add path traversal protection

- `LocalBlobStorage` now normalizes every resolved key and rejects keys that escape or equal the storage root before any file IO, throwing core `BlobValidationException`.
- Rejected patterns include `../` parent traversal, absolute keys such as `/etc/passwd`, and self-resolving keys like `nested/..`; valid nested keys (including redundant `.` segments) still resolve.
- Added temporary-directory tests proving escapes never touch the filesystem outside the root and that pre-existing sibling files remain unchanged.
- Modules affected: `dedup4j-storage-local` and Epic 4 planning/status documentation.

## 2026-08-26 — Implement local put/get/delete/exists

- Added `LocalBlobStorage`, a filesystem `BlobStorage` adapter that streams uploads under the configured root directory, creates parent directories on demand, and overwrites existing objects.
- `get` returns an owner-managed `BlobResource` stream and throws core `ContentNotFoundException` for missing objects; `delete` is idempotent through `Files.deleteIfExists`; `exists` reflects filesystem state.
- Added JUnit temporary-directory integration coverage for the round trip, idempotent missing-object delete, overwrite behavior, existence checks, and blank-key rejection.
- Modules affected: `dedup4j-storage-local` and Epic 4 planning/status documentation.

## 2026-08-26 — Add local storage module

- Added the `dedup4j-storage-local` Maven module to the reactor with a dependency on `dedup4j-core` and no cloud SDKs.
- Added `LocalBlobStorageProperties` with a configurable root directory (`blob-helper-storage` default) and null-rejection on assignment.
- Added property coverage for defaults, custom roots, and null rejection; full `./mvnw verify` passes.
- Modules affected: root reactor, `dedup4j-storage-local`, and Epic 4 planning/status documentation.

## 2026-08-26 — Add provider auto-configuration validation

- Added `Dedup4jAutoConfiguration` registered through Spring Boot `AutoConfiguration.imports`, enabling `Dedup4jProperties` and a startup provider validator.
- Startup now fails clearly for unsupported provider names, missing provider beans, selected providers without a matching bean (`<provider>BlobStorage` naming convention), and ambiguous multi-provider contexts.
- Added `ApplicationContextRunner` coverage for provider selection and every failure mode; added test-scope `spring-boot-test` and AssertJ to the starter module.
- Epic 3 is complete (5/5). Modules affected: `dedup4j-spring-boot-starter` and planning/status documentation.

## 2026-08-25 — Add duplicate-content upload orchestration

- Added pre-write content identity lookup and lock-aware reference retention for duplicate uploads.
- Duplicate uploads now skip `BlobStorage.put`, reuse the existing physical object, and return `BlobReference.duplicate = true`.
- Added service coverage for one physical write, one metadata row, and `ref_count` increasing from one to two.
- Modules affected: `dedup4j-spring-boot-starter` and Epic 3 planning/status documentation.

## 2026-08-24 — Add new-content upload orchestration

- Added buffered upload hashing, deterministic object-key generation, provider-neutral storage writes, and JPA metadata creation through `DefaultBlobDeduplicationService`.
- Added Hibernate/H2 service coverage for one physical write, one metadata row with `ref_count = 1`, and a non-duplicate `BlobReference`.
- Modules affected: `dedup4j-spring-boot-starter` and Epic 3 planning/status documentation.

## 2026-08-22 — Add BlobDeduplicationService contract

- Added the provider-neutral `BlobDeduplicationService` API and default retain/release/get facade.
- Added missing-content contract coverage and starter dependencies on the core and JPA modules.
- Modules affected: `dedup4j-spring-boot-starter` and Epic 3 planning/status documentation.

## 2026-08-21 — Add Spring Boot starter properties

- Added the `dedup4j-spring-boot-starter` Maven module and `Dedup4jProperties` binding for storage, deduplication, and cleanup configuration.
- Added Spring Boot binding coverage for provider selection, upload-size parsing, content-type validation, physical deletion, and disabled-by-default reconciliation.
- Modules affected: root reactor, `dedup4j-spring-boot-starter`, and Epic 3 planning/status documentation.

## 2026-08-21 — Add concurrent duplicate upload integration test

- Added `ConcurrentUploadIntegrationTest` with separate JPA transactions coordinated to race on the same content identity.
- Verified concurrent duplicate uploads converge on one `AssetContent` row with one retained reference per worker.
- Modules affected: `dedup4j-jpa` tests and Epic 2 planning/status documentation.

## 2026-08-20 — Add duplicate-key retry behavior

- Added `AssetContentMutationService.createOrRetain` with explicit insert flushing, duplicate-key transaction retry, locked winner reload, and exactly-once reference incrementing.
- Added coordinated Hibernate/H2 coverage for new content, existing duplicates, and concurrent insert races without storage collaborators.
- Modules affected: `dedup4j-jpa`, Epic 2 planning/status documentation, and the living implementation indexes.

## 2026-08-20 — Add final-reference delete orchestration

- Added `BlobStorage` collaboration to `ReferenceCountService` and delete delegation only when a release reaches zero references.
- Added JPA integration coverage for non-final releases, final physical deletion, and exactly-once delete behavior.
- Modules affected: `dedup4j-jpa` and Epic 2 planning/status documentation.

## 2026-08-20 — Add reference retain and release service

- Added lock-aware `ReferenceCountService` retain and release operations with missing-content and underflow protection.
- Added JPA/Hibernate tests for exactly-once increment/decrement behavior and zero-count release rejection.
- Modules affected: `dedup4j-jpa`, `dedup4j-core` dependency boundary, and Epic 2 planning/status documentation.

## 2026-08-20 — Add AssetContent repository lookups and locks

- Added an EntityManager-backed repository for complete content identity lookup and pessimistic write-locked id lookup.
- Added Hibernate/H2 tests for lookup behavior, missing rows, lock mode, constructor validation, and duplicate identity rejection.
- Modules affected: `dedup4j-jpa` and Epic 2 planning/status documentation.

## 2026-08-18 — Add global PR writer workflow

- Added the reusable `pr-writer` skill for Claude and Codex based on the KADO-65 pull request style.
- Required both agents to use the skill for pull request titles and bodies and recorded baseline and forward validation evidence.
- Areas affected: repository agent instructions, workflow documentation, and global agent skill installations.

## 2026-08-18 — Add JPA metadata module and AssetContent entity

- Added the Jakarta Persistence module and mapped physical blob metadata with identity uniqueness, indexes, timestamps, and optimistic locking.
- Added Hibernate/H2 mapping tests and updated the Maven reactor, planning status, and living project index.
- Modules affected: root reactor, `dedup4j-jpa`, and `docs`.

## 2026-07-16 — Add core dependency boundary enforcement

- Added a classpath-scanning JUnit boundary test and Maven Enforcer rules for Spring, JPA, AWS SDK, and Azure SDK dependencies.
- Completed Epic 1 and updated `dedup4j-core` plus its planning/status documentation.

## 2026-07-15 — Add storage-neutral SPI and models

- Added provider-neutral storage contracts, immutable command/result models, resource stream lifecycle handling, and domain exceptions.
- Affected `dedup4j-core` and the Epic 1 planning/status documentation.

## 2026-07-06 — Add deterministic object key generation

- Added `ObjectKeyStrategy` and `HashObjectKeyStrategy` in `dedup4j-core`.
- Added tests for deterministic hash-derived key generation, lowercase algorithm segments, and empty-prefix relative keys.
- Updated Epic 1 task status and implementation docs for the new `core/key` package.

## 2026-07-04 — Add GitHub Actions CI

- Added `.github/workflows/ci.yml` for Java 21 Maven verification.
- Updated architecture and implementation indexes with the CI entry point.

## 2026-07-04 — Add PR message style guide

- Added `docs/pr-message-style.md` based on Edem's existing PR writeups.
- Updated `docs/README.md` to link the PR message style guide.

## 2026-07-04 — Initial index

- First codebase-indexer scan of dedup4j.
- Generated `docs/architecture.md`, `docs/implementation.md`, `docs/patterns.md`, `docs/decisions.md`, and `docs/changelog.md`.
- Detected Java 21 Maven reactor with current `dedup4j-core` module and planning docs for JPA, Spring Boot starter, local storage, S3/Azure adapters, and reconciliation.
## 2026-08-30 — Add Micrometer metrics

- Added optional Micrometer counters and timers for upload outcomes, deduplication savings, hashing and storage latency, delete failures, and reconciliation repairs.
- Instrumented the starter service and reconciliation flow while preserving no-registry compatibility.
- Modules affected: `dedup4j-spring-boot-starter`, Epic 6 planning/status documentation, and the living implementation indexes.
## 2026-09-01 — Align dashboard monitoring with demo console

- Fixed dashboard polling compatibility with the demo management URL and explicit `since` parameter binding.
- Restyled the read-only dashboard with the demo console's slate, amber, blue, and light/dark theme system.
- Modules affected: `dedup4j-dashboard` and the demo registration configuration.

## 2026-09-01 — Show current metrics on dashboard registration

- The first successful poll now records an instance's existing cumulative metrics instead of displaying an empty baseline.
- Updated delta-calculation and multi-instance integration coverage.
- Modules affected: `dedup4j-dashboard`.

## 2026-09-01 — Fix dashboard refresh and metric layout

- Fixed the overview refresh error caused by a stale duplicate-rate selector and made rendering resilient to optional UI elements.
- Removed the duplicate avoided-bytes side card, aligned the fleet table columns, and styled the `LATEST EVENT` label with the dashboard blue accent.
- Modules affected: `dedup4j-dashboard` static HTML, CSS, and JavaScript.

## 2026-09-01 — Balance dashboard overview and settings alignment

- Reduced the hero panel scale and widened the supporting metrics area for a more even first-view composition.
- Restored the four-column desktop configuration row so “Polling interval / 30 seconds” aligns with the other settings; mobile remains two columns.
- Modules affected: `dedup4j-dashboard` static CSS.

## 2026-09-01 — Fix first configuration cell inset

- Restored the left padding on the first “Polling interval” settings cell so it aligns with the other configuration entries.
- Modules affected: `dedup4j-dashboard` static CSS.

## 2026-09-06 — Upgrade to Spring Boot 4.1.1

- Upgraded the root Spring Boot BOM from 3.5.10 to 4.1.1 and removed obsolete Boot 3 database auto-configuration exclusions from provider discovery coverage.
- Migrated the standalone dashboard to Boot 4 MVC/Jackson starters and Jackson 3's `tools.jackson` API while preserving metrics polling behavior.
- Modules affected: root Maven reactor, `dedup4j-spring-boot-starter`, `dedup4j-dashboard`, and project architecture/implementation/decision documentation.

## 2026-09-03 — Aggregate provider adapters in the standard starter

- Made the Spring Boot starter the single consumer dependency for local, S3, and Azure storage adapters while preserving provider-local SDK ownership.
- Added Maven dependency-convergence enforcement, Dependabot updates, and a pull-request dependency-review gate; documented the packaging contract and classpath/boundary tests, and repaired the task-index/ADR documentation references found during final review.
- Modules affected: root Maven build, `dedup4j-spring-boot-starter`, `dedup4j-core` boundary tests, `.github` automation, and project documentation.

## 2026-09-25 — Rename core module to dedup4j

- Renamed the core artifact, source packages, and root exception to `dedup4j-core`, `com.edem.dedup4j.core`, and `Dedup4jException`.
- Updated consumer imports/dependencies and affected documentation paths; domain contracts and hashing remain unchanged.
- Full clean reactor verification passed; no architectural decision changed.

## 2026-09-25 — Rename jpa module to dedup4j

- Renamed the JPA artifact and packages, persistence-unit class references, downstream imports, and affected documentation; schema names remain pending the persistence compatibility decision.
- Clean verification passed across all ten modules (189 tests); no architectural decision changed.

## 2026-09-25 — Rename local storage module to dedup4j

- Renamed the local adapter artifact and packages, consumer references, and module documentation; retained the existing storage-directory default pending persistence work.
- Clean verification passed across all ten modules (189 tests); no architectural decision changed.

## 2026-09-25 — Rename s3 module to dedup4j

- Renamed the S3 adapter artifact and packages, consumer references, SDK ownership assertions, and affected documentation; provider behavior is unchanged.
- Clean verification passed across all ten modules (189 tests); no architectural decision changed.

## 2026-09-25 — Rename azure module to dedup4j

- Renamed the Azure adapter artifact and packages, consumer references, SDK ownership assertions, and affected documentation; in-process Azure contract tests passed.
- Clean verification passed across all ten modules (189 tests); no architectural decision changed.

## 2026-09-25 — Rename spring boot starter to dedup4j

- Renamed the starter artifact, API facade, packages, Spring discovery registrations and bean names; updated configuration binding and metric producers/readers together. Corrected stale Binder test prefixes; full verification passed.
- Clean verification passed across all ten modules (189 tests); no architectural decision changed.

## 2026-09-25 — Rename management module to dedup4j

- Renamed management packages/artifact, discovery classes, opt-in properties, registration settings, thread names and default routes; synchronized downstream contract fixtures and documentation.
- Clean verification passed across all ten modules (189 tests); no architectural decision changed.

## 2026-09-25 — Rename embedded dashboard module to dedup4j

- Renamed embedded dashboard packages, properties, default route, resource handler and static resources together; updated branding and browser-storage keys. Default/custom route integration and downstream classpath tests passed.
- Clean verification passed across all ten modules (189 tests); no architectural decision changed.

## 2026-09-25 — Rename observability aggregate to dedup4j

- Renamed the dependency-only aggregate artifact and test source paths, preserving component auto-configuration and exclusion of the standalone fleet dashboard.
- Clean verification passed across all ten modules (189 tests); no architectural decision changed.

## 2026-09-25 — Rename standalone dashboard to dedup4j

- Renamed fleet dashboard artifact, packages, runtime settings and UI branding while leaving original SQLite files in place. Fixed explicit history-path binding with a regression test. Packaged UI, JS/CSS and all four read API routes returned HTTP 200 against temporary SQLite; smoke process stopped. Visual inspection unavailable because no browser is connected.
- Clean verification passed across all ten modules (190 tests); no architectural decision changed.

## 2026-09-25 — Rename parent and finish independent documentation cleanup

- Renamed the parent coordinates and legacy root application sources; updated authored docs, examples, package paths, and remaining UI label.
- Added migration mappings and preserved-data inventory; persistence changes remain gated on the compatibility decision.
- Added narrow ignores for fleet SQLite data, sidecars, and backups. Reviewed original runtime files read-only; both passed integrity checks.
- Counted 190 tests from the current reactor only; former-module generated reports are excluded from totals.
- Final clean reactor verification passed (190 tests); packaged HTTP smoke checks passed and all relative Markdown links resolve.
