# ADR-010: Combined Observability Starter

**Date:** 2026-09-03
**Status:** Accepted
**Deciders:** Edem and project maintainers

## Context

The management API and embedded dashboard serve one application's observability experience but currently appear as separate artifacts. Developers should be able to install both with one optional dependency without adding web/UI behavior to the generic upload starter.

## Decision

Add `dedup4j-spring-boot-observability` as an optional aggregate starter. It transitively supplies the existing management module and embedded dashboard starter while leaving their source packages and enablement controls separate. The embedded dashboard keeps its current default-on behavior when installed; management endpoints retain explicit enablement for safety.

The standalone `dedup4j-dashboard` remains a separate executable for multi-instance registration, polling, SQLite history, and fleet views.

## Invariants (from Q2)

- [ ] The generic upload starter contains no management controllers or dashboard resources.
- [ ] One optional observability dependency supplies management and embedded dashboard modules.
- [ ] All observability routes remain read-only.
- [ ] Existing enable/disable properties retain their safety behavior.
- [ ] Standalone fleet monitoring remains a separate application and persistence model.

## Architectural Ownership (from Q3)

| Concern | Owner |
|---|---|
| Aggregate dependency | `dedup4j-spring-boot-observability` |
| Management endpoints | `dedup4j-spring-boot-management` |
| Embedded UI | `dedup4j-spring-boot-dashboard` |
| Fleet polling/history | standalone `dedup4j-dashboard` |

**Explicitly excluded layers:** `dedup4j-core`, generic upload starter, provider adapters.

## Consequences

**Positive:**
- One optional dependency installs the single-application observability experience.
- Existing modules and their tests remain reusable independently.

**Negative / Trade-offs:**
- One additional aggregate artifact must be published and documented.
- Management endpoints still require explicit enablement after installation.

**Risks if violated:**
- Observability dependencies could leak into every upload consumer.
- Combining implementation packages could blur single-instance and fleet responsibilities.

## Rejected Alternatives

### Alternative A: Put management and UI into the generic starter
- Why considered: absolute minimum artifact count.
- Why rejected: forces controllers and static UI into applications that only need uploads.

### Alternative B: Merge the existing source modules
- Why considered: fewer reactor modules.
- Why rejected: loses the independent management contract and makes future headless monitoring harder.

## Related

- ADR-005
- ADR-006
- Implementation Plan: PLAN-013
