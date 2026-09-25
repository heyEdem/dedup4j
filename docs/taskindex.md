# Blob Helper Task Index

**Last Updated:** 2026-09-01
**Completed:** 43/43 (100%)
**Status:** Complete

Checkbox states: `[x]` done · `[~]` in progress · `[ ]` pending. Task IDs are `<epic>.<seq>`.

## Progress Summary

| Epic | Title | Progress | Status |
|---|---|---:|---|
| 1 | Core library | 5/5 | Complete |
| 2 | JPA metadata and reference counting | 6/6 | Complete |
| 3 | Spring Boot starter and service API | 5/5 | Complete |
| 4 | Local storage and integration tests | 4/4 | Complete |
| 5 | S3 and Azure storage adapters | 5/5 | Complete |
| 6 | Reconciliation and observability | 5/5 | Complete |
| 7 | Local dashboard and multi-instance monitoring | 5/5 | Complete |
| 8 | Embedded dashboard starter | 8/8 | Complete |
| **Total** | | **43/43** | **Complete** |

## Source Map

| Epic | ADRs                                                                                                                                                                                             | Implementation Plan | Epic Folder |
|---|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|---|---|
| 1 | [ADR-001](adrs/ADR-001-content-identity-and-core-boundaries.md), [ADR-004](adrs/ADR-004-pluggable-storage-and-spring-boot-starter.md)                                         | [PLAN-001](implementation-plans/PLAN-001-core-library.md) | [epic-001-core-library](epics/epic-001-core-library/README.md) |
| 2 | [ADR-001](adrs/ADR-001-content-identity-and-core-boundaries.md), [ADR-002](adrs/ADR-002-deduplicated-upload-reference-counting.md), [ADR-003](adrs/ADR-003-release-delete-and-reconciliation.md) | [PLAN-002](implementation-plans/PLAN-002-jpa-metadata-and-reference-counting.md) | [epic-002-jpa-metadata-reference-counting](epics/epic-002-jpa-metadata-reference-counting/README.md) |
| 3 | [ADR-002](adrs/ADR-002-deduplicated-upload-reference-counting.md), [ADR-004](adrs/ADR-004-pluggable-storage-and-spring-boot-starter.md)                                                          | [PLAN-003](implementation-plans/PLAN-003-spring-boot-starter-service-api.md) | [epic-003-spring-boot-starter-service-api](epics/epic-003-spring-boot-starter-service-api/README.md) |
| 4 | [ADR-004](adrs/ADR-004-pluggable-storage-and-spring-boot-starter.md)                                                                                                                             | [PLAN-004](implementation-plans/PLAN-004-local-storage-adapter-and-integration-tests.md) | [epic-004-local-storage-integration-tests](epics/epic-004-local-storage-integration-tests/README.md) |
| 5 | [ADR-004](adrs/ADR-004-pluggable-storage-and-spring-boot-starter.md)                                                                                                                             | [PLAN-005](implementation-plans/PLAN-005-s3-azure-storage-adapters.md) | [epic-005-s3-azure-storage-adapters](epics/epic-005-s3-azure-storage-adapters/README.md) |
| 6 | [ADR-003](adrs/ADR-003-release-delete-and-reconciliation.md)                                                                                                                                     | [PLAN-006](implementation-plans/PLAN-006-reconciliation-observability.md) | [epic-006-reconciliation-observability](epics/epic-006-reconciliation-observability/README.md) |
| 7 | [ADR-005](adrs/ADR-005-local-dashboard-pull-monitoring.md)                                                                                                                                       | [PLAN-007](implementation-plans/PLAN-007-local-dashboard-monitoring.md) | [epic-007-local-dashboard-monitoring](epics/epic-007-local-dashboard-monitoring/README.md) |
| 8 | [ADR-006](adrs/ADR-006-embedded-dashboard-starter.md)                                                                                                                                            | [PLAN-008](implementation-plans/PLAN-008-embedded-dashboard-starter.md) | — |

## Verification Commands

| Purpose | Command |
|---|---|
| Full test suite | `./mvnw test` |
| Core module tests | `./mvnw -pl dedup4j-core test` |
| JPA module tests | `./mvnw -pl dedup4j-jpa test` |
| Starter tests | `./mvnw -pl dedup4j-spring-boot-starter test` |
| Local storage tests | `./mvnw -pl dedup4j-storage-local test` |
| Azure module tests | `./mvnw -pl dedup4j-storage-azure test` |
| Dependency boundary checks | `./mvnw test -Dtest='*BoundaryTest'` |
| Dashboard module tests | `./mvnw -pl blob-helper-spring-boot-management,blob-helper-dashboard test` |
| Inspect tracked planning docs | `git status --short docs` |

## Epic 1 — Core Library

- [x] 1.1 [Create Maven multi-module foundation](epics/epic-001-core-library/tasks/task-001-create-maven-multimodule-foundation.md)
- [x] 1.2 [Add streaming content hashing](epics/epic-001-core-library/tasks/task-002-add-streaming-content-hashing.md)
- [x] 1.3 [Add deterministic object key generation](epics/epic-001-core-library/tasks/task-003-add-deterministic-object-key-generation.md)
- [x] 1.4 [Add storage-neutral SPI and models](epics/epic-001-core-library/tasks/task-004-add-storage-neutral-spi-and-models.md)
- [x] 1.5 [Add core dependency boundary tests](epics/epic-001-core-library/tasks/task-005-add-core-dependency-boundary-tests.md)

## Epic 2 — JPA Metadata and Reference Counting

- [x] 2.1 [Add JPA module and AssetContent entity](epics/epic-002-jpa-metadata-reference-counting/tasks/task-001-add-jpa-module-and-asset-content-entity.md)
- [x] 2.2 [Add repository identity lookups and locks](epics/epic-002-jpa-metadata-reference-counting/tasks/task-002-add-repository-identity-lookups-and-locks.md)
- [x] 2.3 [Add reference retain and release service](epics/epic-002-jpa-metadata-reference-counting/tasks/task-003-add-reference-retain-and-release-service.md)
- [x] 2.4 [Add final-reference delete orchestration](epics/epic-002-jpa-metadata-reference-counting/tasks/task-004-add-final-reference-delete-orchestration.md)
- [x] 2.5 [Add duplicate-key retry behavior](epics/epic-002-jpa-metadata-reference-counting/tasks/task-005-add-duplicate-key-retry-behavior.md)
- [x] 2.6 [Add concurrent duplicate upload integration test](epics/epic-002-jpa-metadata-reference-counting/tasks/task-006-add-concurrent-duplicate-upload-integration-test.md)

## Epic 3 — Spring Boot Starter and Service API

- [x] 3.1 [Add starter module and properties](epics/epic-003-spring-boot-starter-service-api/tasks/task-001-add-starter-module-and-properties.md)
- [x] 3.2 [Add BlobDeduplicationService contract](epics/epic-003-spring-boot-starter-service-api/tasks/task-002-add-blob-deduplication-service-contract.md)
- [x] 3.3 [Implement new-content upload orchestration](epics/epic-003-spring-boot-starter-service-api/tasks/task-003-implement-new-content-upload-orchestration.md)
- [x] 3.4 [Implement duplicate-content upload orchestration](epics/epic-003-spring-boot-starter-service-api/tasks/task-004-implement-duplicate-content-upload-orchestration.md)
- [x] 3.5 [Add provider auto-configuration validation](epics/epic-003-spring-boot-starter-service-api/tasks/task-005-add-provider-auto-configuration-validation.md)

## Epic 4 — Local Storage and Integration Tests

- [x] 4.1 [Add local storage module](epics/epic-004-local-storage-integration-tests/tasks/task-001-add-local-storage-module.md)
- [x] 4.2 [Implement local put get delete exists](epics/epic-004-local-storage-integration-tests/tasks/task-002-implement-local-put-get-delete-exists.md)
- [x] 4.3 [Add path traversal protection](epics/epic-004-local-storage-integration-tests/tasks/task-003-add-path-traversal-protection.md)
- [x] 4.4 [Add local storage service integration tests](epics/epic-004-local-storage-integration-tests/tasks/task-004-add-local-storage-service-integration-tests.md)

## Epic 5 — S3 and Azure Storage Adapters

- [x] 5.1 [Add S3 storage module](epics/epic-005-s3-azure-storage-adapters/tasks/task-001-add-s3-storage-module.md)
- [x] 5.2 [Implement S3 BlobStorage adapter](epics/epic-005-s3-azure-storage-adapters/tasks/task-002-implement-s3-blob-storage-adapter.md)
- [x] 5.3 [Add Azure storage module](epics/epic-005-s3-azure-storage-adapters/tasks/task-003-add-azure-storage-module.md)
- [x] 5.4 [Implement Azure BlobStorage adapter](epics/epic-005-s3-azure-storage-adapters/tasks/task-004-implement-azure-blob-storage-adapter.md)
- [x] 5.5 [Add provider SDK boundary and contract tests](epics/epic-005-s3-azure-storage-adapters/tasks/task-005-add-provider-sdk-boundary-and-contract-tests.md)

## Epic 6 — Reconciliation and Observability

- [x] 6.1 [Add reconciliation contracts](epics/epic-006-reconciliation-observability/tasks/task-001-add-reconciliation-contracts.md)
- [x] 6.2 [Implement mismatch reporting](epics/epic-006-reconciliation-observability/tasks/task-002-implement-mismatch-reporting.md)
- [x] 6.3 [Implement opt-in repair](epics/epic-006-reconciliation-observability/tasks/task-003-implement-opt-in-repair.md)
- [x] 6.4 [Add Micrometer metrics](epics/epic-006-reconciliation-observability/tasks/task-004-add-micrometer-metrics.md)
- [x] 6.5 [Add structured operational logging](epics/epic-006-reconciliation-observability/tasks/task-005-add-structured-operational-logging.md)

## Epic 7 — Local Dashboard and Multi-Instance Monitoring

- [x] 7.1 [Add management module and local read-only API](epics/epic-007-local-dashboard-monitoring/tasks/task-001-add-management-module-and-local-read-only-api.md)
- [x] 7.2 [Add YAML self-registration and dashboard shell](epics/epic-007-local-dashboard-monitoring/tasks/task-002-add-yaml-self-registration-and-dashboard-shell.md)
- [x] 7.3 [Add SQLite polling history and failure retention](epics/epic-007-local-dashboard-monitoring/tasks/task-003-add-sqlite-polling-history-and-failure-retention.md)
- [x] 7.4 [Add dashboard API and light/dark static UI](epics/epic-007-local-dashboard-monitoring/tasks/task-004-add-dashboard-api-and-static-ui.md)
- [x] 7.5 [Add multi-instance end-to-end verification](epics/epic-007-local-dashboard-monitoring/tasks/task-005-add-multi-instance-end-to-end-verification.md)

## Epic 8 — Embedded Dashboard Starter

- [x] 8.1 Record the embedded-dashboard architecture decision
- [x] 8.2 Add the dashboard starter module and properties
- [x] 8.3 Add the current-instance metrics snapshot
- [x] 8.4 Add the embedded dashboard API and auto-configuration
- [x] 8.5 Serve the embedded static UI
- [x] 8.6 Verify standalone dashboard compatibility
- [x] 8.7 Add consumer documentation and Maven artifact policy
- [x] 8.8 Run full verification and prepare the release boundary

## Notes

### Integration add-ons

- [x] PLAN-011 — Consumer JPA and schema auto-configuration, completed 2026-09-07. Includes transaction-neutral duplicate signaling, guarded prefixed migrations, conditional service assembly, Spring retry, and the local concurrent-publication correction. Clean full-reactor verification: 168 tests passed. See [execution record](plans/2026-09-06-plan011-execution.md). Delivered locally under PLAN-011's Git exclusion; the original 43-task milestone totals above are unchanged.

| Date | Note |
|---|---|
| 2026-09-01 | Completed Epic 8 with the optional embedded dashboard starter, current-process read-only API, default-on MVC auto-configuration, relative-path static UI, custom base-path support, standalone compatibility verification, and full reactor verification. Project scope is now 43/43 tasks complete. |
| 2026-08-31 | Completed task 7.5 with two-instance in-process management polling, independent failure/status verification, dashboard contract coverage, and credential-free seven-day retention verification. Epic 7 and the project are complete (35/35). |
| 2026-08-31 | Completed task 7.4 with read-only overview/history/failure APIs, responsive vanilla dashboard UI, explicit operational states, and persisted light/dark theme choice. Project scope is now 34/35 tasks complete. |
| 2026-08-31 | Completed task 7.3 with SQLite-backed registrations, interval metric snapshots, independent scheduled polling, reset-safe deltas, persisted instance status, and seven-day failure cleanup. Project scope is now 33/35 tasks complete. |
| 2026-08-31 | Completed task 7.2 with asynchronous YAML-configured self-registration, stable instance IDs, idempotent dashboard registration, and an executable loopback dashboard shell. Project scope is now 32/35 tasks complete. |
| 2026-08-31 | Completed task 7.1 with the optional management module, disabled-by-default GET-only API, provider-neutral snapshots, and no cloud SDK dependencies. Project scope is now 31/35 tasks complete. |
| 2026-08-30 | Completed task 6.5 with structured upload decision logs, short explicit hash prefixes, and failed physical-delete context. Epic 6 is complete (5/5); project scope is now 30/35 tasks complete. |
| 2026-08-30 | Completed task 6.4 with optional Micrometer counters/timers for upload outcomes, deduplication savings, hashing/storage latency, delete failures, and repairs. Epic 6 is in progress (4/5). |
| 2026-08-29 | Completed task 6.3 with explicit repair enablement, read-only disabled behavior, and lock-aware retain/release repair operations. Epic 6 is in progress (3/5). |
| 2026-08-29 | Completed task 6.2 with read-only mismatch reporting across stored content rows, application-provided counts, omitted-ID detection, and no-mutation verification. Epic 6 is in progress (2/5). |
| 2026-08-28 | Completed task 6.1 with application-owned logical reference count contracts and immutable reconciliation report/mismatch values. Epic 6 is in progress (1/5). |
| 2026-08-28 | Added approved Epic 7 for a fully local, read-only, pull-based dashboard with YAML self-registration, SQLite aggregate history, and seven-day failure retention. Project scope is now 25/35 tasks complete. |
| 2026-08-27 | Completed task 5.5 with reactor POM ownership checks for AWS/Azure SDKs, credential-free provider-testing documentation, and a passing `ProviderDependencyBoundaryTest`. Epic 5 is complete (5/5). |
| 2026-08-27 | Completed task 5.4 with streaming Azure put/get, idempotent delete, existence checks, core exception mapping, and credential-free SDK-backed contract tests using an in-process HTTP fake. Epic 5 is in progress (4/5). |
| 2026-08-27 | Completed task 5.3 with the isolated `dedup4j-storage-azure` module, Azure SDK BOM/dependency, and configurable container, connection string, endpoint, and account name properties. Epic 5 is in progress (3/5). |
| 2026-08-26 | Completed task 5.2 with streaming S3 put/get, idempotent delete, head-based existence checks, domain exception mapping, and credential-free contract tests. Epic 5 is in progress (2/5). |
| 2026-08-26 | Completed task 5.1 with the isolated `dedup4j-storage-s3` module, AWS SDK v2 module-local BOM, and configurable bucket, region, endpoint override, and path-style access properties. Epic 5 is in progress. |
| 2026-08-26 | Completed task 4.4 with a real local-provider service integration test covering temporary-directory storage, readback, duplicate physical-write avoidance, and final-reference deletion. Epic 4 is complete. |
| 2026-08-26 | Completed task 4.3 with normalized key resolution, containment checks against the storage root, and rejection of `../`, absolute-path, and self-resolving keys before file IO. Epic 4 is in progress. |
| 2026-08-26 | Completed task 4.2 with the filesystem `LocalBlobStorage` adapter implementing put/get/idempotent delete/exists against a configurable root directory. Epic 4 is in progress. |
| 2026-08-26 | Completed task 4.1 with the `dedup4j-storage-local` reactor module, a `dedup4j-core` dependency with no cloud SDKs, and configurable root-directory properties. Epic 4 is in progress. |
| 2026-08-26 | Completed task 3.5 with `Dedup4jAutoConfiguration`, registered via `AutoConfiguration.imports`, and a startup validator that fails clearly for unsupported, missing, and ambiguous providers. Epic 3 is complete. |
| 2026-08-25 | Completed task 3.4 with pre-write content identity lookup, lock-aware duplicate reference retention, and no-op physical storage for duplicate uploads. |
| 2026-08-24 | Completed task 3.3 with buffered upload hashing, deterministic object-key generation, one physical storage write, and `AssetContent` creation through the JPA create-or-retain service. |
| 2026-08-22 | Completed task 3.2 with the provider-neutral `BlobDeduplicationService` contract, default retain/release/get facade, and missing-content coverage. |
| 2026-08-21 | Completed task 3.1 with the Spring Boot starter module, `blob-helper.*` properties binding, upload-size parsing, and disabled-by-default reconciliation. |
 | 2026-08-21 | Completed task 2.6 with a coordinated two-transaction duplicate upload integration test that verifies one identity row and one retained reference per worker. |
| 2026-08-20 | Completed task 2.5 with create-or-retain duplicate-key retry, locked winner reload, exact reference increment, and coordinated JPA race coverage. |
| 2026-08-20 | Completed task 2.4 with final-reference physical delete delegation, non-final release protection, and idempotent storage-delete coverage. |
| 2026-08-20 | Completed task 2.3 with lock-aware retain/release operations, core exception handling, and underflow tests. |
| 2026-08-20 | Completed task 2.2 with transaction-scoped identity lookup and pessimistic lock repository operations plus duplicate identity tests. |
| 2026-08-18 | Completed task 2.1 with the JPA module, `AssetContent` mapping, and Hibernate/H2 mapping tests. |
| 2026-07-16 | Completed task 1.5 with classpath scanning and Maven Enforcer dependency boundary checks; Epic 1 is complete. |
| 2026-07-15 | Completed task 1.4 with the provider-neutral storage SPI, models, and domain exception hierarchy. |
| 2026-07-04 | Task index generated from `docs/adrs/ADR-001..ADR-004.md` and `docs/implementation-plans/PLAN-001..PLAN-006.md`. |
