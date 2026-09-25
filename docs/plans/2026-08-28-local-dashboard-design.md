# Local Multi-Instance Dashboard Design

**Date:** 2026-08-28
**Status:** Approved
**Scope:** MVP dashboard and local instance monitoring

## Goal

Provide a lightweight, RabbitMQ-style local administration dashboard for
monitoring multiple Blob Helper-enabled Spring Boot applications. The dashboard
must show operational trends and recent failures without owning blob bytes,
application logical assets, or cloud-provider credentials.

## Decisions

- The dashboard is a separate `dedup4j-dashboard` Spring Boot application.
- A separate optional `dedup4j-spring-boot-management` module exposes the
  read-only management API and self-registration client for consuming apps.
- Instances self-register with the local dashboard using `application.yaml`.
- The dashboard pulls health, counters, current content totals, and recent
  failures from registered instances.
- The dashboard stores registrations and aggregate history in SQLite.
- Aggregate metrics are retained long-term; individual failure details are
  retained for seven days.
- The MVP is read-only. It cannot delete blobs, repair counts, or mutate an
  instance.
- The dashboard binds to `127.0.0.1` by default and has no authentication in
  the fully local MVP.
- Remote access, authentication, push collection, billing data, and dashboard
  write actions are later features.

## Architecture

```text
Blob Helper application A ─┐
Blob Helper application B ─┼─ local management API
Blob Helper application C ─┘          ▲
                                     │ pull
                          dedup4j-dashboard
                          127.0.0.1:9090
                                     │
                                  SQLite
```

The management module is optional and does not change the application’s
business-facing upload API. It exposes provider-neutral operational data and a
startup registration client. The dashboard owns the instance registry,
polling, normalization of cumulative counters into time-series samples, and
the static web UI.

## Management Contract

The management module exposes local, read-only endpoints below its configured
base path:

- `GET /dedup4j/management/v1/info`: instance identity, display name,
  provider, and management API version.
- `GET /dedup4j/management/v1/health`: current availability and storage
  provider status.
- `GET /dedup4j/management/v1/metrics`: cumulative operation counters,
  byte counters, latency summaries, and current content totals.
- `GET /dedup4j/management/v1/failures?since=<timestamp>`: recent failure
  details for dashboard collection.

The dashboard registration endpoint accepts an instance ID, name, advertised
management URL, provider name, and API version. Registration is local-only and
does not require a token in the MVP. Poll failures mark an instance stale or
disconnected; instances do not need to send a separate heartbeat.

## Configuration

An instance opts in to management and self-registration:

```yaml
blob-helper:
  management:
    enabled: true
    base-path: /dedup4j/management
  dashboard-registration:
    enabled: true
    dashboard-url: http://127.0.0.1:9090
    instance-name: orders-service
    advertised-url: http://127.0.0.1:8081/dedup4j/management
```

The dashboard uses normal Spring Boot server settings and dashboard-specific
settings:

```yaml
server:
  address: 127.0.0.1
  port: 9090

blob-helper:
  dashboard:
    database-path: ./blob-helper-dashboard.sqlite
    polling-interval: 30s
    failure-retention: 7d
```

## Persistence

The dashboard stores:

- Registered instance ID, name, URL, provider, status, and last-seen time.
- Poll-time metric snapshots and normalized interval deltas.
- Failure ID, instance ID, timestamp, operation, error type, message, and
  optional correlation ID.

Successful operations are not stored as individual events. Failure rows older
than seven days are deleted during normal polling or startup cleanup.

## UI

The static frontend is served by the dashboard JAR and uses HTML, CSS, and
vanilla JavaScript. It includes:

- Overview: connected instances, upload attempts, duplicate rate, bytes
  written, bytes saved, and provider health.
- Instances: all registered applications with status and last poll time.
- Instance detail: logical versus physical traffic, operation trends, and
  recent failures.
- Failures: seven-day searchable and filterable failure table.
- Settings: polling, retention, and local instance configuration visibility.

Light and dark themes use shared CSS variables and respect the system theme by
default. The MVP is read-only and provides no deletion or repair controls.

## Non-Goals

- No direct dashboard access to an instance’s database or object store.
- No S3/Azure billing or provider-wide traffic accounting.
- No remote dashboard exposure or authentication.
- No raw successful-upload event log.
- No automatic cleanup or reconciliation repair from the UI.

## Success Criteria

The MVP is successful when a developer can start the local dashboard, start
multiple configured Blob Helper applications, see them self-register, view
their current status, inspect historical aggregate traffic and deduplication
savings, and review the last seven days of failure details without cloud
credentials or additional infrastructure.
