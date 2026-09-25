# ADR-007: Generic Starter and Provider Auto-configuration

**Date:** 2026-09-03  
**Status:** Accepted  
**Deciders:** Edem and project maintainers

## Context

The current integration requires consumers to add the base starter and a provider adapter, then manually create provider and service beans. Configuration can select only implementations already on the classpath, but consumers should not need to understand the internal module graph. Bundling provider adapters increases dependency size and compatibility responsibility.

## Decision

`dedup4j-spring-boot-starter` is the single standard consumer dependency and transitively includes the local, S3, and Azure adapter modules. Provider implementation code remains inside those modules. The provider auto-configuration planned for PLAN-010 will create only the provider selected by `dedup4j.storage.provider`, reuse an application-provided provider client when available, and otherwise create the client from standard SDK configuration.

For S3, provider and bucket are the only Blob Helper-required properties. Region, endpoint, and path style are optional overrides. Dashboard and management modules remain outside this starter.

## Invariants (from Q2)

- [ ] Provider SDK dependencies are declared only in their provider adapter modules.
- [ ] Exactly one configured `BlobStorage` is created.
- [ ] Unselected providers do not initialize clients, discover credentials, or require properties.
- [ ] An application-provided provider client takes precedence over the default.
- [ ] Normal S3 setup requires only provider and bucket from Blob Helper.
- [ ] Dashboard and management are not transitive dependencies of the generic upload starter.
- [ ] Dependency convergence, provider contracts, and vulnerability checks guard the bundled graph.

## Architectural Ownership (from Q3)

| Concern | Owner |
|---|---|
| Consumer dependency aggregation | `dedup4j-spring-boot-starter/pom.xml` |
| Provider conditions and property binding | starter auto-configuration package |
| AWS implementation | `dedup4j-storage-s3` |
| Azure implementation | `dedup4j-storage-azure` |
| Local implementation | `dedup4j-storage-local` |
| Convergence/security gates | root Maven build and `.github` workflows |

**Explicitly excluded layers:** `dedup4j-core`, JPA entities, management/dashboard modules.

## Consequences

**Positive:**
- One dependency provides a configuration-driven installation experience.
- Existing application client customization is preserved.
- Provider implementation boundaries remain independently testable.

**Negative / Trade-offs:**
- Every standard consumer carries unused provider SDK dependencies.
- Blob Helper maintainers own a larger compatibility and vulnerability surface.

**Risks if violated:**
- Unselected clients may trigger credential or startup failures.
- SDK types may leak into provider-neutral APIs.
- Dependency drift may cause runtime linkage errors.

## Rejected Alternatives

### Alternative A: Base starter plus provider adapter
- Why considered: smallest provider-specific classpath.
- Why rejected: exposes internal module assembly and violates the one-dependency outcome.

### Alternative B: Provider-specific convenience starters
- Why considered: one direct dependency without unused SDKs.
- Why rejected: creates several public installation paths instead of one generic quick start.

## Related

- Supersedes the packaging decision in ADR-004.
- Implementation Plan: PLAN-009
- Implementation Plan: PLAN-010
