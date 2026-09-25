# ADR-002: Deduplicated Upload Reference Counting

**Date:** 2026-07-04  
**Status:** Proposed  
**Deciders:** Project maintainers

## Context

The main value of Blob Helper is avoiding repeated physical writes for identical bytes while allowing each application to create its own logical asset records. Concurrent uploads of identical bytes are expected.

## Decision

The upload service computes content identity, looks up existing content, and either creates a new `AssetContent` with `ref_count = 1` or increments the existing row. Duplicate uploads must skip object storage writes. Concurrent duplicate uploads are handled with a database unique constraint, transactional insert, duplicate-key retry, row locking, and deterministic object keys.

## Invariants (from Q2)

- [ ] Duplicate uploads must skip physical storage upload.
- [ ] New content must create exactly one `AssetContent` row with `ref_count = 1`.
- [ ] Duplicate content must increment `ref_count` exactly once per logical retain.
- [ ] Concurrent identical uploads must converge on one content row.
- [ ] Duplicate-key races must reload the existing row and increment it.

## Architectural Ownership (from Q3)

| Concern | Owner |
|---------|-------|
| Upload orchestration | `dedup4j-spring-boot-starter` upload service |
| Lookup, insert, locking, and reference count mutation | `dedup4j-jpa` |
| Physical writes | `BlobStorage` implementation |
| Logical asset creation | Consuming application |

**Explicitly excluded layers:** REST controllers, provider adapters, consuming application asset tables.

## Consequences

**Positive:**
- Application behavior stays transparent while physical storage is deduplicated.
- Database constraints provide the final concurrency guard.
- Provider adapters remain simple blob IO implementations.

**Negative / Trade-offs:**
- Upload orchestration must handle storage/database ordering carefully.
- Duplicate-key retry behavior must be integration tested.

**Risks if violated:**
- Concurrent uploads may create duplicate rows or wrong reference counts.
- Duplicate uploads may still write physical objects.
- Business logic may scatter into controllers or adapters.

## Rejected Alternatives

### Alternative A: Let storage adapters detect duplicates
- Why considered: adapters know whether objects exist.
- Why rejected: violates ownership because deduplication is metadata and reference-count behavior, not storage IO behavior.

### Alternative B: Application-owned reference counting
- Why considered: applications own logical assets.
- Why rejected: violates the reusable physical content ownership boundary and would duplicate fragile logic in each app.

## Related

- ADR-001
- Implementation Plan: PLAN-002
- Implementation Plan: PLAN-003
