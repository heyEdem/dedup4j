# ADR-005: Local Pull-Based Multi-Instance Dashboard

**Date:** 2026-08-28
**Status:** Proposed
**Deciders:** Project maintainers

## Context

Blob Helper needs an operational view for developers and operators. The view
should resemble a lightweight management console, support multiple local
instances, demonstrate deduplication savings over time, and remain independent
of consuming application databases and provider credentials.

## Decision

Add a separate `dedup4j-dashboard` Spring Boot application and an optional
`dedup4j-spring-boot-management` module. Blob Helper instances opt in with
`application.yaml`, self-register with the local dashboard, and expose local
read-only management endpoints. The dashboard polls those endpoints and stores
instance registrations, aggregate metric snapshots, and seven days of failure
details in SQLite.

The MVP binds the dashboard and registration flow to `127.0.0.1`, requires no
authentication, and exposes no mutation actions. Remote access, authentication,
push collection, and repair controls are deferred features.

## Invariants

- [ ] The dashboard must not own application logical asset records or blob bytes.
- [ ] Storage-provider credentials must remain in the consuming application.
- [ ] Instance monitoring must use a read-only management contract.
- [ ] Dashboard collection must support multiple registered instances.
- [ ] Aggregate metrics must be retained independently from seven-day failure details.
- [ ] The dashboard must bind to `127.0.0.1` by default.
- [ ] The MVP must not expose deletion, repair, or other write actions.

## Architectural Ownership

| Concern | Owner |
|---------|-------|
| Read-only instance management API | `dedup4j-spring-boot-management` |
| Instance self-registration client | `dedup4j-spring-boot-management` |
| Registration, polling, normalization, and SQLite persistence | `dedup4j-dashboard` |
| Dashboard REST API and static UI | `dedup4j-dashboard` |
| Blob bytes and provider credentials | Consuming application/provider configuration |

**Explicitly excluded layers:** `dedup4j-core`, storage adapters, and the
consuming application’s logical asset controllers.

## Consequences

**Positive:**

- Local setup needs only the dashboard JAR and SQLite file.
- Multiple application instances can be monitored from one console.
- Pull collection keeps instance instrumentation and dashboard persistence
  loosely coupled.
- Aggregate history demonstrates the bytes saved by deduplication.
- The dashboard remains read-only and safe for an initial operations tool.

**Negative / Trade-offs:**

- The dashboard cannot observe an instance while its management endpoint is
  unreachable.
- Local-only, tokenless access is not suitable for remote deployment.
- Dashboard history is based on observed polls rather than provider billing
  records.

## Rejected Alternatives

### Alternative A: Embedded dashboard in the starter

Rejected because every consuming application would carry and serve a UI, and
there would be no natural central view across multiple instances.

### Alternative B: Push-only telemetry

Rejected for the MVP because it requires delivery retries, buffering, and
dashboard ingestion semantics before a useful local console exists.

### Alternative C: Direct dashboard database/storage access

Rejected because it couples the dashboard to every consuming application’s
schema and exposes provider-specific credentials and failure modes.

## Deferred Features

- Optional authentication for remote or non-loopback deployments.
- Push-based collection for restricted networks.
- Dashboard-triggered reconciliation and repair.
- Provider billing or CloudWatch/Azure Monitor integration.

## Related

- [ADR-003](ADR-003-release-delete-and-reconciliation.md)
- [ADR-004](ADR-004-pluggable-storage-and-spring-boot-starter.md)
- [Local dashboard design](../plans/2026-08-28-local-dashboard-design.md)
