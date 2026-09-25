# PLAN-006: Reconciliation and Observability

**Date:** 2026-07-04  
**Status:** Complete
**Implements:** ADR-003  
**Estimated complexity:** Medium

## Goal

Add drift reporting, optional repair, metrics, and structured logs for Blob Helper operations.

## Background

Reference counts can drift if consuming applications fail after partial operations. Operators also need metrics for deduplication value and cleanup failures.

The planned [PLAN-007 local dashboard](PLAN-007-local-dashboard-monitoring.md)
will consume the metrics and failure signals produced by this plan. Dashboard
registration, polling, SQLite persistence, and UI concerns remain outside this
plan.

## What to Build

- Add a reconciliation service with an application-provided logical reference count source.
- Report mismatches between expected logical references and stored `ref_count`.
- Add opt-in repair behavior.
- Add optional scheduled reconciliation, disabled by default.
- Add metrics for uploads, duplicates, skipped physical writes, accepted bytes, avoided bytes, latencies, delete failures, and repairs.
- Add structured logs with content id, provider, object key, hash prefix, and duplicate/new decision.

## Where the Logic Lives (from Q3)

| Logic | Location |
|-------|----------|
| Reconciliation contracts | `blob-helper-spring-boot-starter/src/main/java/.../reconcile` |
| Reference count queries/updates | `dedup4j-jpa` |
| Metrics and logs | Starter service orchestration |
| Application logical count input | App-provided callback/query adapter |

## Acceptance Criteria (from Q4)

- [x] **ReconciliationServiceTest.reportsReferenceCountMismatch:** Given actual and expected counts differ, when reconciliation runs, then a mismatch report is returned.
- [x] **ReconciliationServiceTest.repairsOnlyWhenEnabled:** Given repair disabled, when reconciliation finds drift, then no database mutation occurs.
- [x] **BlobHelperMetricsTest.recordsDuplicateAndSkippedUpload:** Given a duplicate upload, then duplicate and skipped-upload metrics are incremented.
- [x] **BlobHelperLoggingTest.logsHashPrefixOnlyByDefault:** Given an upload, then logs include an explicit hash prefix and do not add a separate full content hash field.

## Out of Scope (from Q5)

- Scheduled repairs enabled by default — forbidden.
- Assumptions about consuming application logical asset schema — use callbacks/adapters.
- Full hash logging by default — forbidden.

## Implementation Notes

- Use Micrometer if Spring Boot starter dependencies already provide it.
- Keep repair commands explicit and auditable.
- Log failed physical deletes for later reconciliation.

## Definition of Done

- [x] All acceptance criteria tests pass
- [x] No out-of-scope files were modified
- [x] ADR invariants are enforced in code
- [ ] PR reviewed and merged
