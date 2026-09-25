# ADR-006: Make the embedded dashboard the primary developer experience

## Status

Accepted

## Context

Blob Helper already provides a local management API and a standalone dashboard
application for aggregating multiple registered applications. That mode is
useful for fleet monitoring, but it requires a second process and SQLite
storage even when a developer only wants to inspect one Spring Boot
application.

## Decision

Add an optional `dedup4j-spring-boot-dashboard` starter. When present on a
web application's classpath, it serves a read-only dashboard at
`/dedup4j/dashboard` and exposes current-process metrics under
`/dedup4j/dashboard/api/v1`. The embedded dashboard is enabled by default
and can be disabled with `dedup4j.dashboard.enabled=false`.

The standalone `blob-helper-dashboard` application remains a separate
multi-instance fleet-monitoring application. It retains instance registration,
pull polling, SQLite history, and seven-day failure retention. Embedded mode
does not register with or persist data in the standalone dashboard.

## Alternatives considered

- Keep the standalone application as the only dashboard: poor single-
  application developer experience.
- Move dashboard controllers into the core starter: couples core integration to
  web/UI concerns.
- Share SQLite and polling with embedded mode: adds persistence and scheduling
  to every consuming application.

## Consequences

- One optional dependency makes a current-process dashboard available without a
  second Java process.
- Embedded views are current-process snapshots and have no fleet history.
- The dashboard remains read-only and cannot mutate blobs, metadata, or repair
  reference counts.
- The standalone dashboard continues to serve multi-instance operators.
