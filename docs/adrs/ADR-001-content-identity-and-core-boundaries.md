# ADR-001: Content Identity and Core Boundaries

**Date:** 2026-07-04  
**Status:** Proposed  
**Deciders:** Project maintainers

## Context

dedup4j deduplicates byte-identical uploads across application-owned logical assets. The library must identify physical content consistently while remaining reusable across Spring Boot applications and object storage providers.

## Decision

Content identity is `hash_algorithm + content_hash + size_bytes`. The first hash algorithm is streaming SHA-256, encoded as lowercase hex. Generated object keys use deterministic hash-derived keys and never trust user filenames. `dedup4j-core` owns hashing, storage-neutral models, object-key strategy, and the `BlobStorage` SPI, and must not depend on Spring, JPA, AWS, or Azure.

## Invariants (from Q2)

- [ ] Content identity must use `hash_algorithm + content_hash + size_bytes`.
- [ ] SHA-256 hashes must be computed from the exact uploaded bytes while streaming.
- [ ] Generated object keys must not be controlled by user filenames.
- [ ] `dedup4j-core` must stay framework-neutral.

## Architectural Ownership (from Q3)

| Concern | Owner |
|---------|-------|
| Hashing contracts and SHA-256 implementation | `dedup4j-core` |
| Storage-neutral request/response models | `dedup4j-core` |
| Object key generation | `dedup4j-core` |
| Content identity uniqueness | `dedup4j-jpa` |

**Explicitly excluded layers:** controllers, storage adapters, consuming application logical asset models.

## Consequences

**Positive:**
- Deduplication is stable across storage providers.
- Core code can be tested without Spring or cloud SDKs.
- User filenames remain metadata only.

**Negative / Trade-offs:**
- Hashing must complete before final duplicate detection.
- Future hash algorithms require explicit identity versioning.

**Risks if violated:**
- Duplicate bytes may be stored more than once.
- Provider-specific code may leak into reusable core APIs.
- User-controlled filenames could become unsafe storage keys.

## Rejected Alternatives

### Alternative A: Hash-only identity
- Why considered: simpler lookup key.
- Why rejected: violates the content identity invariant by dropping `size_bytes`, which the spec requires as an additional guard.

### Alternative B: Provider-generated object keys
- Why considered: lets storage providers choose native key formats.
- Why rejected: weakens deterministic duplicate upload convergence and moves core identity behavior into adapters.

## Related

- Implementation Plan: PLAN-001
- Implementation Plan: PLAN-002
