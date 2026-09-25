# Changelog

## 2026-09-07 — Reconcile feature branch with Spring Boot 4 main

- Resolved the documentation conflicts while preserving the Boot 4.1.1/Jackson 3 migration and the PLAN-011/012/013 implementation history.
- Updated persistence auto-configuration to Boot 4 package locations and public Liquibase configuration APIs, and kept invalid provider selection errors ahead of service construction.
- Updated Spring Boot 4 test fixtures for relocated entity scanning and Spring Framework 7 transaction status APIs.
- Modules affected: `blob-helper-spring-boot-starter`, root build metadata, and project documentation.

## 2026-09-07 — Add combined observability starter

- Added the dependency-only `blob-helper-spring-boot-observability` aggregate for the embedded management and current-application dashboard modules.
- Added classpath, activation, disablement, servlet, and read-only route coverage while keeping the generic upload starter and standalone fleet dashboard separate.
- Documented the one-dependency embedded setup and the explicit `blob-helper.management.enabled=true` choice.
- Modules affected: root Maven reactor, `blob-helper-spring-boot-observability`, `README.md`, and project documentation.

## 2026-09-07 — Add friendly upload facade, stable locations, and ordered batches

- Added provider-neutral `BlobLocation` values and complete locations on new and duplicate `BlobReference` results, plus metadata-only location lookup through the transactional service.
- Added the Spring-facing `BlobHelper` facade for multipart, path, byte-array, and described-stream uploads with size enforcement, and auto-configured it with application back-off.
- Added ordered success/failure batch outcomes with continue-on-failure semantics and MVC usage coverage without introducing a library controller.
- Modules affected: `dedup4j-core`, `dedup4j-jpa`, `blob-helper-spring-boot-starter`, and project documentation.

## 2026-09-07 — Consumer JPA and guarded schema auto-configuration

- Implemented PLAN-011 using Luna workers: consumer entity discovery, independently replaceable defaults, prefixed Liquibase metadata migrations, guarded initialization modes, and actionable schema validation before JPA startup.
- Moved duplicate-key recovery across Spring `REQUIRES_NEW` transactions without replaying upload input or storage writes; retained framework-neutral JPA behavior and application-managed migration configuration.
- Fixed local concurrent publication through same-directory temporary files and atomic replacement; added failed-stream preservation and maximum-filename coverage. Corrected the S3 client test proxy for Spring persistence lifecycle callbacks.
- Updated consumer guidance, architecture/implementation indexes, patterns, decisions, and completion records. Legacy table data requires a separate application-managed transition.
- Modules affected: `dedup4j-jpa`, `blob-helper-spring-boot-starter`, and `blob-helper-storage-local`. Clean full-reactor verification passed all 168 tests across ten modules; no failures, errors, or skips.

## 2026-09-05 — Auto-configure selected storage providers

- Implemented PLAN-010 with nested local/S3/Azure settings, selected-provider client and storage defaults, application-client reuse, and complete back-off for application storage.
- Required explicit provider selection and exactly one storage bean by type; Spring owns S3 client shutdown, and Azure exposes its existing client factory for shared construction.
- Added provider context, client lifecycle, and automatic-discovery coverage. Full reactor verification passed: 137 tests, zero failures, errors, or skips.
- Modules affected: `blob-helper-spring-boot-starter`, `blob-helper-storage-azure`, and consumer/project documentation.

## 2026-09-01 — Fix embedded dashboard root asset paths

- Redirected the no-trailing-slash dashboard URL to its slash form so relative CSS and JavaScript assets resolve correctly.
- Modules affected: `blob-helper-spring-boot-dashboard`.

## 2026-09-01 — Add embedded dashboard starter

- Added the optional `blob-helper-spring-boot-dashboard` module with default-on, read-only current-process API and packaged responsive UI at `/blob-helper/dashboard`.
- Preserved the standalone `blob-helper-dashboard` application for multi-instance SQLite-backed fleet monitoring.
- Modules affected: root reactor, `blob-helper-spring-boot-dashboard`, and project documentation.

## 2026-08-31 — Verify local multi-instance monitoring

- Added management response contract coverage and a two-instance in-process end-to-end test for registration, polling, aggregate savings, failure isolation, and seven-day retention cleanup.
- Updated local-only startup, verification, and completion documentation for Epic 7.
- Modules affected: `blob-helper-spring-boot-management`, `blob-helper-dashboard`, and project documentation.

## 2026-08-31 — Add dashboard API and static console

- Added read-only overview, instance status, history, and seven-day failure endpoints.
- Added responsive vanilla HTML/CSS/JavaScript console with light/dark themes, trend visualization, and explicit loading, empty, disconnected, and error states.
- Modules affected: `blob-helper-dashboard` and Epic 7 planning/status documentation.

## 2026-08-31 — Persist dashboard polling history

- Added SQLite-backed instance registrations, interval metric snapshots, persisted status, and failure events with indexed instance/time queries.
- Added scheduled multi-instance polling with reset-safe cumulative counter deltas and seven-day failure-only retention cleanup.
- Modules affected: `blob-helper-dashboard` and Epic 7 planning/status documentation.

## 2026-08-31 — Add self-registration and dashboard shell

- Added the standalone executable `blob-helper-dashboard` module with loopback/9090 defaults and local registration endpoint.
- Added asynchronous management-side self-registration with YAML configuration, stable generated instance IDs, and outage isolation.
- Modules affected: `blob-helper-dashboard`, `blob-helper-spring-boot-management`, root Maven reactor, and Epic 7 planning/status documentation.

## 2026-08-31 — Add local read-only management API

- Added the optional `blob-helper-spring-boot-management` module with disabled-by-default Spring Boot auto-configuration.
- Added provider-neutral info, health, metrics, and recent-failure response contracts with GET-only endpoints.
- Modules affected: `blob-helper-spring-boot-management`, root Maven reactor, and Epic 7 planning/status documentation.

## 2026-08-30 — Add structured operational logging

- Added structured upload decision logs for new and duplicate content with content ID, provider, object key, decision, size, and an explicit short hash prefix.
- Added failed physical-delete logs with reconciliation context and SLF4J/Logback-backed coverage.
- Completed Epic 6 (5/5); project scope is now 30/35 tasks complete.
- Modules affected: `blob-helper-spring-boot-starter` and Epic 6 planning/status documentation.

## 2026-08-29 — Implement opt-in reconciliation repair

- Added an explicit, disabled-by-default repair operation that converges drift through the lock-aware JPA reference-count boundary.
- Added coverage for enabled repairs, disabled no-mutation behavior, and final-reference physical deletion.

## 2026-08-29 — Implement reconciliation mismatch reporting

- Added a read-only reconciliation service that compares stored reference counts with application-provided logical counts and reports omitted IDs as zero expected references.
- Added a repository query for loading all physical content metadata and Hibernate/H2 integration coverage proving mismatch details and no database mutation.
- Modules affected: `dedup4j-jpa`, `blob-helper-spring-boot-starter`, and Epic 6 planning/status documentation.

## 2026-08-28 — Add reconciliation contracts

- Added an application-owned logical reference count callback plus immutable validated reconciliation report and mismatch records.
- Kept report generation independent from repair commands and consuming-application schema details.
- Modules affected: `blob-helper-spring-boot-starter` and Epic 6 planning/status documentation.

## 2026-08-28 — Define local multi-instance dashboard

- Added the approved design and ADR for a fully local, read-only, pull-based dashboard with YAML self-registration.
- Added Epic 7 and PLAN-007 for the optional management module, standalone dashboard, SQLite aggregate history, light/dark UI, and seven-day failure retention.
- Updated the specification, architecture, implementation index, five-question requirements, patterns, README, and testing guidance.
- Modules affected: planned `blob-helper-spring-boot-management`, planned `blob-helper-dashboard`, and project documentation.

## 2026-08-27 — Add provider SDK boundary checks

- Added `ProviderDependencyBoundaryTest` to verify AWS and Azure SDK ownership remains isolated to their provider modules.
- Added `docs/provider-testing.md` documenting credential-free default tests and opt-in external provider test execution.
- Epic 5 is complete (5/5); the project task index is 25/30 complete.
- Modules affected: `dedup4j-core`, root Maven test configuration, provider-testing documentation, and Epic 5 planning/status documentation.

## 2026-08-27 — Implement Azure BlobStorage adapter

- Added streaming Azure Blob Storage put/get, idempotent delete, existence checks, and core exception mapping.
- Added credential-free SDK-backed contract tests using an in-process HTTP fake.
- Modules affected: `blob-helper-storage-azure` and Epic 5 planning/status documentation.

## 2026-08-27 — Add Azure storage module

- Added `blob-helper-storage-azure` to the Maven reactor with a module-local Azure SDK BOM and `azure-storage-blob` dependency.
- Added `AzureBlobStorageProperties` for container, connection string, endpoint, and account name configuration without Azure SDK types.
- Added credential-free properties coverage and verified the focused module test, Azure dependency tree, and full reactor build.
- Modules affected: root reactor, `blob-helper-storage-azure`, and Epic 5 planning/status documentation.

## 2026-08-26 — Implement S3 BlobStorage adapter

- Added streaming S3 put/get, idempotent delete, and head-based exists behavior with bucket and object metadata mapping.
- Mapped S3 404 responses to core not-found semantics and other provider failures to `BlobStorageException`; added credential-free contract tests because no external S3-compatible target is configured in the repository.
- Modules affected: `blob-helper-storage-s3` and Epic 5 planning/status documentation.

## 2026-08-26 — Add S3 storage module

- Added `blob-helper-storage-s3` to the Maven reactor with a module-local AWS SDK for Java 2.x BOM and S3 dependency.
- Added configurable S3 properties for bucket, region, optional endpoint override, and path-style access, which defaults to disabled.
- Verified AWS SDK isolation from `dedup4j-core` and `blob-helper-spring-boot-starter`; Epic 5 is in progress (1/5).
- Modules affected: root reactor, `blob-helper-storage-s3`, and Epic 5 planning/status documentation.

## 2026-08-26 — Add local storage service integration tests

- Added `LocalStorageDeduplicationIntegrationTest` covering the real service, JPA metadata, and temporary-directory local storage flow.
- Verified service readback, duplicate uploads retaining one physical file, and physical deletion after the final reference release.
- Added `blob-helper-storage-local` as a test-scoped starter dependency; Epic 4 is complete (4/4).
- Modules affected: `blob-helper-spring-boot-starter`, `blob-helper-storage-local`, and Epic 4 planning/status documentation.

## 2026-08-26 — Add path traversal protection

- `LocalBlobStorage` now normalizes every resolved key and rejects keys that escape or equal the storage root before any file IO, throwing core `BlobValidationException`.
- Rejected patterns include `../` parent traversal, absolute keys such as `/etc/passwd`, and self-resolving keys like `nested/..`; valid nested keys (including redundant `.` segments) still resolve.
- Added temporary-directory tests proving escapes never touch the filesystem outside the root and that pre-existing sibling files remain unchanged.
- Modules affected: `blob-helper-storage-local` and Epic 4 planning/status documentation.

## 2026-08-26 — Implement local put/get/delete/exists

- Added `LocalBlobStorage`, a filesystem `BlobStorage` adapter that streams uploads under the configured root directory, creates parent directories on demand, and overwrites existing objects.
- `get` returns an owner-managed `BlobResource` stream and throws core `ContentNotFoundException` for missing objects; `delete` is idempotent through `Files.deleteIfExists`; `exists` reflects filesystem state.
- Added JUnit temporary-directory integration coverage for the round trip, idempotent missing-object delete, overwrite behavior, existence checks, and blank-key rejection.
- Modules affected: `blob-helper-storage-local` and Epic 4 planning/status documentation.

## 2026-08-26 — Add local storage module

- Added the `blob-helper-storage-local` Maven module to the reactor with a dependency on `dedup4j-core` and no cloud SDKs.
- Added `LocalBlobStorageProperties` with a configurable root directory (`blob-helper-storage` default) and null-rejection on assignment.
- Added property coverage for defaults, custom roots, and null rejection; full `./mvnw verify` passes.
- Modules affected: root reactor, `blob-helper-storage-local`, and Epic 4 planning/status documentation.

## 2026-08-26 — Add provider auto-configuration validation

- Added `BlobHelperAutoConfiguration` registered through Spring Boot `AutoConfiguration.imports`, enabling `BlobHelperProperties` and a startup provider validator.
- Startup now fails clearly for unsupported provider names, missing provider beans, selected providers without a matching bean (`<provider>BlobStorage` naming convention), and ambiguous multi-provider contexts.
- Added `ApplicationContextRunner` coverage for provider selection and every failure mode; added test-scope `spring-boot-test` and AssertJ to the starter module.
- Epic 3 is complete (5/5). Modules affected: `blob-helper-spring-boot-starter` and planning/status documentation.

## 2026-08-25 — Add duplicate-content upload orchestration

- Added pre-write content identity lookup and lock-aware reference retention for duplicate uploads.
- Duplicate uploads now skip `BlobStorage.put`, reuse the existing physical object, and return `BlobReference.duplicate = true`.
- Added service coverage for one physical write, one metadata row, and `ref_count` increasing from one to two.
- Modules affected: `blob-helper-spring-boot-starter` and Epic 3 planning/status documentation.

## 2026-08-24 — Add new-content upload orchestration

- Added buffered upload hashing, deterministic object-key generation, provider-neutral storage writes, and JPA metadata creation through `DefaultBlobDeduplicationService`.
- Added Hibernate/H2 service coverage for one physical write, one metadata row with `ref_count = 1`, and a non-duplicate `BlobReference`.
- Modules affected: `blob-helper-spring-boot-starter` and Epic 3 planning/status documentation.

## 2026-08-22 — Add BlobDeduplicationService contract

- Added the provider-neutral `BlobDeduplicationService` API and default retain/release/get facade.
- Added missing-content contract coverage and starter dependencies on the core and JPA modules.
- Modules affected: `blob-helper-spring-boot-starter` and Epic 3 planning/status documentation.

## 2026-08-21 — Add Spring Boot starter properties

- Added the `blob-helper-spring-boot-starter` Maven module and `BlobHelperProperties` binding for storage, deduplication, and cleanup configuration.
- Added Spring Boot binding coverage for provider selection, upload-size parsing, content-type validation, physical deletion, and disabled-by-default reconciliation.
- Modules affected: root reactor, `blob-helper-spring-boot-starter`, and Epic 3 planning/status documentation.

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

- First codebase-indexer scan of Blob Helper.
- Generated `docs/architecture.md`, `docs/implementation.md`, `docs/patterns.md`, `docs/decisions.md`, and `docs/changelog.md`.
- Detected Java 21 Maven reactor with current `dedup4j-core` module and planning docs for JPA, Spring Boot starter, local storage, S3/Azure adapters, and reconciliation.
## 2026-08-30 — Add Micrometer metrics

- Added optional Micrometer counters and timers for upload outcomes, deduplication savings, hashing and storage latency, delete failures, and reconciliation repairs.
- Instrumented the starter service and reconciliation flow while preserving no-registry compatibility.
- Modules affected: `blob-helper-spring-boot-starter`, Epic 6 planning/status documentation, and the living implementation indexes.
## 2026-09-01 — Align dashboard monitoring with demo console

- Fixed dashboard polling compatibility with the demo management URL and explicit `since` parameter binding.
- Restyled the read-only dashboard with the demo console's slate, amber, blue, and light/dark theme system.
- Modules affected: `blob-helper-dashboard` and the demo registration configuration.

## 2026-09-01 — Show current metrics on dashboard registration

- The first successful poll now records an instance's existing cumulative metrics instead of displaying an empty baseline.
- Updated delta-calculation and multi-instance integration coverage.
- Modules affected: `blob-helper-dashboard`.

## 2026-09-01 — Fix dashboard refresh and metric layout

- Fixed the overview refresh error caused by a stale duplicate-rate selector and made rendering resilient to optional UI elements.
- Removed the duplicate avoided-bytes side card, aligned the fleet table columns, and styled the `LATEST EVENT` label with the dashboard blue accent.
- Modules affected: `blob-helper-dashboard` static HTML, CSS, and JavaScript.

## 2026-09-01 — Balance dashboard overview and settings alignment

- Reduced the hero panel scale and widened the supporting metrics area for a more even first-view composition.
- Restored the four-column desktop configuration row so “Polling interval / 30 seconds” aligns with the other settings; mobile remains two columns.
- Modules affected: `blob-helper-dashboard` static CSS.

## 2026-09-01 — Fix first configuration cell inset

- Restored the left padding on the first “Polling interval” settings cell so it aligns with the other configuration entries.
- Modules affected: `blob-helper-dashboard` static CSS.

## 2026-09-06 — Upgrade to Spring Boot 4.1.1

- Upgraded the root Spring Boot BOM from 3.5.10 to 4.1.1 and removed obsolete Boot 3 database auto-configuration exclusions from provider discovery coverage.
- Migrated the standalone dashboard to Boot 4 MVC/Jackson starters and Jackson 3's `tools.jackson` API while preserving metrics polling behavior.
- Modules affected: root Maven reactor, `blob-helper-spring-boot-starter`, `blob-helper-dashboard`, and project architecture/implementation/decision documentation.

## 2026-09-03 — Aggregate provider adapters in the standard starter

- Made the Spring Boot starter the single consumer dependency for local, S3, and Azure storage adapters while preserving provider-local SDK ownership.
- Added Maven dependency-convergence enforcement, Dependabot updates, and a pull-request dependency-review gate; documented the packaging contract and classpath/boundary tests, and repaired the task-index/ADR documentation references found during final review.
- Modules affected: root Maven build, `blob-helper-spring-boot-starter`, `dedup4j-core` boundary tests, `.github` automation, and project documentation.

## 2026-09-25 — Rename core module to dedup4j

- Renamed the core artifact, source packages, and root exception to `dedup4j-core`, `com.edem.dedup4j.core`, and `Dedup4jException`.
- Updated consumer imports/dependencies and affected documentation paths; domain contracts and hashing remain unchanged.
- Full clean reactor verification passed; no architectural decision changed.

## 2026-09-25 — Rename jpa module to dedup4j

- Renamed the JPA artifact and packages, persistence-unit class references, downstream imports, and affected documentation; schema names remain pending the persistence compatibility decision.
- Clean verification passed across all ten modules (189 tests); no architectural decision changed.
