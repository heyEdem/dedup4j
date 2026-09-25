# ADR-009: Friendly Upload Facade and Batch Outcomes

**Date:** 2026-09-03
**Status:** Accepted
**Deciders:** Edem and project maintainers

## Context

`StoreBlobCommand` is an appropriate provider-neutral request but is too ceremonial as the primary Spring MVC experience. Applications also need multiple input forms, ordered batch uploads, application-owned responses, and stable storage locations for later provider-specific access such as presigning.

## Decision

The starter exposes an auto-configured `Dedup4j` facade. It accepts `MultipartFile`, `Path`, `byte[]`, and a size-described `InputStream`, builds `StoreBlobCommand` internally, and delegates physical work to `BlobDeduplicationService`. Arbitrary media types are allowed by default and configured upload-size limits are enforced.

`storeAll(MultipartFile[])` processes every item sequentially by default, continues after failures, preserves input order, and returns one indexed success or failure outcome per item. Each item has an independent metadata transaction.

Single stores return a non-optional `BlobReference`. dedup4j persists and exposes `BlobLocation(provider, bucketOrContainer, objectKey)`. Consuming applications own logical records, controller responses, and presigned URLs.

## Invariants (from Q2)

- [ ] dedup4j is the sole physical upload path.
- [ ] New bytes cause one physical write; duplicates cause none.
- [ ] Every successful call creates or increments exactly one reference.
- [ ] All approved input sources preserve filename, content type, size, and metadata when supplied.
- [ ] Batch processing reports every input in original order and continues after failures.
- [ ] The batch API never claims all-or-nothing storage atomicity.
- [ ] Stable locations contain provider, bucket/container, and key.
- [ ] dedup4j never creates or persists presigned URLs.
- [ ] dedup4j adds no application upload controller or logical asset entity.

## Architectural Ownership (from Q3)

| Concern | Owner |
|---|---|
| Stable location value | `dedup4j-core` |
| Deduplicated store/location lookup | `BlobDeduplicationService` |
| Spring input adaptation and batching | starter `Dedup4j` facade |
| Physical IO | selected `BlobStorage` adapter |
| Logical record, HTTP DTO, presigning | consuming application |

**Explicitly excluded layers:** dedup4j controllers, provider clients called directly by the facade, consuming application schemas.

## Consequences

**Positive:**
- Common upload code becomes one line.
- Batch partial success is explicit and recoverable.
- Applications can preserve their existing response models.

**Negative / Trade-offs:**
- Spring MVC types enter the Spring-facing facade.
- Batch callers must inspect per-item outcomes.
- One-shot streams still require an explicit byte size and the existing replay/buffering behavior.

**Risks if violated:**
- A second application-side provider upload defeats deduplication.
- Fail-fast batch behavior may conceal successful earlier writes.
- Persisting access URLs would create stale or sensitive data.

## Rejected Alternatives

### Alternative A: Return `void`
- Why considered: smallest call surface.
- Why rejected: hides the content ID and stable location needed for logical records and future lifecycle work.

### Alternative B: Return `Optional<BlobReference>` for duplicates
- Why considered: signal a skipped physical write.
- Why rejected: duplicates are successful retained references, not absent results.

### Alternative C: Atomic batch promise
- Why considered: familiar database-style semantics.
- Why rejected: relational transactions cannot roll back already completed provider writes.

## Related

- Supersedes the primary developer API portion of ADR-004.
- ADR-002
- Implementation Plan: PLAN-012
