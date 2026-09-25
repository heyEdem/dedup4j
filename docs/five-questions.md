# Five Questions

**Date:** 2026-07-04

This document extracts the five-question requirements from `docs/SPECIFICATION.md`.

## FQ-001: Content Identity and Core Boundaries

**Q1 - What outcome are we protecting?**  
Byte-identical content is identified consistently and can be shared by many logical assets without tying the core library to Spring, JPA, AWS, Azure, or a specific application asset model.

**Q2 - What must never break?**
- Content identity must use `hash_algorithm + content_hash + size_bytes`.
- SHA-256 hashes must be computed from the exact uploaded bytes while streaming.
- Generated object keys must not be controlled by user filenames.
- `dedup4j-core` must stay framework-neutral.

**Q3 - Where should this logic live?**
- Hashing contracts, SHA-256 implementation, storage-neutral models, `BlobStorage`, and object-key strategy live in `dedup4j-core`.
- Database uniqueness for content identity lives in `dedup4j-jpa`.

**Q4 - What test proves the rule?**
- `Sha256ContentHasherTest.hashesExactBytes`: given known bytes, when hashed through the streaming hasher, then the lowercase SHA-256 hex digest matches the known value.
- `HashObjectKeyStrategyTest.generatesDeterministicKey`: given a prefix, algorithm, and hash, when generating a key, then the key is `{prefix}/{algorithm}/{first_two_hash_chars}/{content_hash}`.
- `CoreModuleBoundaryTest.coreHasNoSpringJpaOrProviderDependencies`: given `dedup4j-core`, then dependency analysis finds no Spring, JPA, AWS, or Azure dependencies.

**Q5 - What should AI not touch?**
- Do not add Spring, JPA, AWS, or Azure dependencies to `dedup4j-core`.
- Do not make user filenames part of the storage object key.
- Do not create application-owned logical asset tables in this library.

## FQ-002: Deduplicated Upload and Reference Counting

**Q1 - What outcome are we protecting?**  
Uploading bytes that already exist reuses the existing physical content and increments the reference count without writing another object.

**Q2 - What must never break?**
- Duplicate uploads must skip physical storage upload.
- New content must create exactly one `AssetContent` row with `ref_count = 1`.
- Duplicate content must increment `ref_count` exactly once per logical retain.
- Concurrent identical uploads must converge on one content row.
- Duplicate-key races must reload the existing row and increment it.

**Q3 - Where should this logic live?**
- The orchestration lives in the starter upload service.
- Content lookup, insert, locking, and reference count mutation live in `dedup4j-jpa`.
- Physical upload lives only behind the `BlobStorage` SPI.

**Q4 - What test proves the rule?**
- `BlobDeduplicationServiceTest.storesNewContent`: given unseen bytes, when `store` is called, then storage is written once and the content row has `ref_count = 1`.
- `BlobDeduplicationServiceTest.reusesDuplicateContent`: given existing bytes, when `store` is called again, then storage is not written and `ref_count` increases by one.
- `ConcurrentUploadIntegrationTest.concurrentDuplicatesCreateOneRow`: given parallel uploads of identical bytes, when all complete, then one row exists and `ref_count` equals the upload count.

**Q5 - What should AI not touch?**
- Do not put deduplication rules in controllers.
- Do not let storage adapters decide whether content is a duplicate.
- Do not change consuming applications' logical asset ownership.

## FQ-003: Release, Delete, and Cleanup

**Q1 - What outcome are we protecting?**  
Physical blobs are deleted only after the final logical reference is released, and release operations cannot corrupt reference counts.

**Q2 - What must never break?**
- `ref_count` must never go below zero.
- Releasing one of many references must not delete physical storage.
- Releasing the final reference must delete or tombstone the content row according to configuration.
- Storage delete must be idempotent by default for missing objects.
- Storage failures after metadata changes must be visible for reconciliation.

**Q3 - Where should this logic live?**
- Reference locking and underflow protection live in `dedup4j-jpa`.
- Release orchestration lives in the starter service.
- Provider deletion behavior lives in `BlobStorage.delete`.
- Repair/reporting lives in the reconciliation service.

**Q4 - What test proves the rule?**
- `ReferenceCountServiceTest.releaseDoesNotDeleteWhenReferencesRemain`: given `ref_count = 2`, when released, then `ref_count = 1` and storage delete is not called.
- `ReferenceCountServiceTest.releaseFinalReferenceDeletesPhysicalObject`: given `ref_count = 1`, when released, then storage delete is called once.
- `ReferenceCountServiceTest.releaseUnderflowFails`: given `ref_count = 0`, when released, then a reference count underflow exception is thrown.
- `BlobStorageDeleteTest.missingObjectIsAlreadyDeletedByDefault`: given a missing object, when deleted, then no exception is thrown unless strict mode is enabled.

**Q5 - What should AI not touch?**
- Do not perform physical deletes from JPA entity callbacks.
- Do not make storage adapters mutate database state.
- Do not silently ignore reference count underflow.

## FQ-004: Pluggable Storage and Spring Boot Starter

**Q1 - What outcome are we protecting?**  
Applications can switch storage providers through dependencies and configuration without changing business logic.

**Q2 - What must never break?**
- Public service APIs must remain storage-neutral.
- Provider-specific settings must stay out of `dedup4j-core`.
- The starter must wire exactly one configured provider.
- Local storage must support deterministic tests without cloud credentials.

**Q3 - Where should this logic live?**
- Provider-neutral contracts live in `dedup4j-core`.
- Auto-configuration and properties live in `dedup4j-spring-boot-starter`.
- Provider SDK code lives only in provider modules.

**Q4 - What test proves the rule?**
- `Dedup4jAutoConfigurationTest.wiresConfiguredProvider`: given `dedup4j.storage.provider=local`, when context starts, then the local `BlobStorage` bean is selected.
- `Dedup4jAutoConfigurationTest.failsForUnsupportedProvider`: given an unsupported provider, when context starts, then startup fails with a clear configuration error.
- `LocalBlobStorageIntegrationTest.putGetDeleteRoundTrip`: given local storage config, when storing, reading, and deleting a blob, then filesystem state matches each operation.

**Q5 - What should AI not touch?**
- Do not expose REST controllers from the starter.
- Do not generate public URLs by default.
- Do not put AWS or Azure SDK code in the starter or core modules.

## FQ-005: Reconciliation and Observability

**Q1 - What outcome are we protecting?**  
Reference count drift, failed deletes, and operational savings are detectable and repairable without requiring scheduled mutation by default.

**Q2 - What must never break?**
- Reconciliation must be disabled by default.
- Repair must be opt-in.
- Reconciliation must compare stored `ref_count` with application-provided logical reference counts.
- Logs must not expose full hashes by default.
- Metrics must distinguish uploads, duplicates, skipped physical writes, and delete failures.

**Q3 - Where should this logic live?**
- Reconciliation contracts and services live in the starter/JPA boundary.
- Metrics and logging live in service orchestration, not provider adapters.
- Application reference counting input comes from an app-provided callback or query adapter.

**Q4 - What test proves the rule?**
- `ReconciliationServiceTest.reportsReferenceCountMismatch`: given actual and expected counts differ, when reconciliation runs, then a mismatch report is returned.
- `ReconciliationServiceTest.repairsOnlyWhenEnabled`: given repair disabled, when reconciliation finds drift, then no database mutation occurs.
- `Dedup4jMetricsTest.recordsDuplicateAndSkippedUpload`: given a duplicate upload, then duplicate and skipped-upload metrics are incremented.

**Q5 - What should AI not touch?**
- Do not enable scheduled repairs by default.
- Do not assume a consuming application's logical asset schema.
- Do not log full content hashes unless explicitly configured.

## FQ-006: Local Dashboard and Multi-Instance Monitoring

**Q1 - What outcome are we protecting?**
Developers and operators can see the health, traffic contribution, deduplication
savings, and recent failures of multiple local Blob Helper instances from one
read-only console.

**Q2 - What must never break?**

- Dashboard collection must not require direct access to application databases,
  blob stores, or provider credentials.
- Instances must be able to self-register through `application.yaml`.
- The dashboard must poll instances independently and preserve history in
  SQLite.
- Aggregate metrics must survive detailed-failure cleanup.
- Detailed failures must be retained for seven days only.
- The local MVP must bind to loopback and expose no mutation actions.

**Q3 - Where should this logic live?**

- Local management endpoints and self-registration live in the optional
  `dedup4j-spring-boot-management` module.
- Registration, polling, persistence, and dashboard APIs/UI live in
  `dedup4j-dashboard`.
- Blob bytes, logical assets, and provider credentials remain with the
  consuming application.

**Q4 - What test proves the rule?**

- `MultiInstanceDashboardIntegrationTest.twoInstancesRegisterAndContributeIndependentMetrics`: two instances register and contribute separate and combined metrics.
- `MetricDeltaCalculatorTest.counterResetStartsNewBaseline`: a restarted instance never creates negative traffic deltas.
- `FailureEventRepositoryTest.retainsFailuresForSevenDays`: old detailed failures are removed while aggregate snapshots remain.
- `DashboardControllerTest.dashboardIsReadOnly`: dashboard routes expose no mutation actions.

**Q5 - What should AI not touch?**

- Do not add remote authentication or remote dashboard exposure to the local MVP.
- Do not store cloud-provider credentials in the dashboard.
- Do not store every successful upload as a raw event.
- Do not add delete or reconciliation-repair buttons to the first dashboard.
