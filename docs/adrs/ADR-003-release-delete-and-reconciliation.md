# ADR-003: Release, Delete, and Reconciliation

**Date:** 2026-07-04  
**Status:** Proposed  
**Deciders:** Project maintainers

## Context

Many logical assets can point to one physical content row. The library must release references safely and delete physical objects only when they are no longer referenced. Failures can cause drift, so operational reconciliation is required.

## Decision

Release operations lock the `AssetContent` row, prevent underflow, decrement `ref_count`, and delete physical storage only when the final reference is released. Missing storage objects are treated as already deleted by default unless strict mode is enabled. Reconciliation reports reference count drift using an app-provided reference count source and repairs only when explicitly enabled.

## Invariants (from Q2)

- [ ] `ref_count` must never go below zero.
- [ ] Releasing one of many references must not delete physical storage.
- [ ] Releasing the final reference must delete or tombstone the content row according to configuration.
- [ ] Storage delete must be idempotent by default for missing objects.
- [ ] Storage failures after metadata changes must be visible for reconciliation.
- [ ] Reconciliation must be disabled by default.
- [ ] Repair must be opt-in.

## Architectural Ownership (from Q3)

| Concern | Owner |
|---------|-------|
| Row locking and underflow protection | `dedup4j-jpa` |
| Release orchestration | `blob-helper-spring-boot-starter` service |
| Physical deletion | `BlobStorage.delete` |
| Drift reporting and repair | Reconciliation service |
| Logical reference count input | Consuming application callback/query adapter |

**Explicitly excluded layers:** entity callbacks, storage adapters mutating database state, scheduled repair enabled by default.

## Consequences

**Positive:**
- Physical deletes are tied to explicit reference lifecycle.
- Underflow becomes a hard failure instead of silent corruption.
- Production drift has a defined detection and repair path.

**Negative / Trade-offs:**
- Reconciliation requires application integration because logical asset schemas are app-owned.
- Delete failure handling needs careful reporting.

**Risks if violated:**
- Shared content may be deleted while still referenced.
- Reference counts may become negative or silently wrong.
- Failed physical deletes may be lost.

## Rejected Alternatives

### Alternative A: Delete blobs from JPA entity callbacks
- Why considered: automatic cleanup near persistence events.
- Why rejected: violates ownership and makes external IO occur from persistence lifecycle hooks.

### Alternative B: Scheduled repair enabled by default
- Why considered: keeps counts correct automatically.
- Why rejected: violates the opt-in repair invariant and may mutate application data unexpectedly.

## Related

- ADR-002
- Implementation Plan: PLAN-002
- Implementation Plan: PLAN-006
