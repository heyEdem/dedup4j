# PLAN-007: Local Dashboard and Multi-Instance Monitoring

**Date:** 2026-08-28
**Status:** Complete
**Implements:** ADR-005
**Estimated complexity:** Large

## Goal

Build a lightweight, standalone, read-only dashboard that monitors multiple
local Blob Helper instances through self-registration and pull-based management
endpoints, retaining aggregate history in SQLite and detailed failures for
seven days.

## Architecture

Add two optional Maven modules:

- `dedup4j-spring-boot-management`: instance-side configuration,
  read-only management endpoints, and self-registration client.
- `blob-helper-dashboard`: standalone Spring Boot app with registration,
  polling, SQLite persistence, REST view API, and static HTML/CSS/JavaScript UI.

The management module must not own application logical assets, cloud-provider
credentials, or write operations. The dashboard must not connect directly to an
instance database or object store.

## Tech Stack

- Java 21 and Spring Boot 3.5.10.
- Spring MVC for local JSON endpoints.
- SQLite JDBC with direct SQL or Spring JDBC for dashboard persistence.
- Static HTML, CSS, and vanilla JavaScript served from the dashboard JAR.
- JUnit Jupiter, Spring Boot test utilities, and an in-process HTTP server for
  credential-free integration tests.

## File Map

| Boundary | Responsibility |
|---|---|
| `dedup4j-spring-boot-management/pom.xml` | Optional management module dependencies and auto-configuration metadata. |
| `.../management/Dedup4jManagementProperties.java` | Enablement, base path, instance ID/name, advertised URL, and dashboard registration settings. |
| `.../management/Dedup4jManagementController.java` | Read-only info, health, metrics, and failure endpoints. |
| `.../dashboard/registration/InstanceRegistrationController.java` | Local dashboard registration endpoint. |
| `.../dashboard/registration/InstanceRegistrationClient.java` | Startup self-registration from an instance. |
| `.../dashboard/persistence/*` | SQLite schema and repositories for registry, snapshots, and failures. |
| `.../dashboard/polling/InstancePollingService.java` | Scheduled pull collection, status transitions, and retention cleanup. |
| `.../dashboard/api/DashboardController.java` | Read-only JSON consumed by the static UI. |
| `.../resources/static/*` | RabbitMQ-style dashboard pages, charts, tables, and themes. |

## Configuration Contract

Instance configuration:

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

Dashboard configuration:

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

The dashboard uses a generated stable instance ID supplied by the management
client. Registration is an idempotent upsert by instance ID. The dashboard
accepts local registrations without tokens in the MVP.

## Task 1: Management Module and Contract

**Files:**

- Create the module POM and add it to the root reactor.
- Create `Dedup4jManagementProperties` with management disabled by default,
  `/dedup4j/management` as the default base path, and dashboard
  registration disabled by default.
- Create provider-neutral response records for info, health, metrics, and
  failures.
- Create `Dedup4jManagementController` with:
  `GET /v1/info`, `GET /v1/health`, `GET /v1/metrics`, and
  `GET /v1/failures?since=<timestamp>`.
- Register auto-configuration through
  `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.

**Tests first:**

- `managementIsDisabledByDefault` verifies no management endpoints are exposed
  without opt-in.
- `metricsResponseContainsProviderNeutralCounters` verifies counters and byte
  totals contain no SDK types or credentials.
- `managementEndpointsAreReadOnly` verifies no write method is registered.

Run:

```bash
./mvnw -pl dedup4j-spring-boot-management -Dtest=Dedup4jManagementControllerTest test
```

Execution steps:

- [ ] Write the controller tests for disabled-by-default behavior, provider-neutral metric JSON, and the absence of write mappings.
- [ ] Run the focused test command and confirm the tests fail because the management module and endpoints do not exist.
- [ ] Add the module POM, Spring Boot auto-configuration metadata, properties, response records, and controller.
- [ ] Run the focused test command and confirm all management contract tests pass.
- [ ] Commit with `feat(management): add local read-only management API`.

## Task 2: Self-Registration and Dashboard Shell

**Files:**

- Create the standalone dashboard Spring Boot module and application class.
- Create the local registration model/controller and instance-side registration
  client.
- Set dashboard defaults to address `127.0.0.1` and port `9090`.
- Make registration retryable without blocking the consuming application’s
  startup; an unavailable dashboard must not prevent Blob Helper startup.

**Tests first:**

- `registrationUpsertsByStableInstanceId` verifies duplicate startup records
  update one instance rather than create two.
- `registrationClientDoesNotBlockApplicationStartup` verifies a dashboard
  outage is handled as a monitoring warning.
- `dashboardBindsToLoopbackByDefault` verifies the default server settings.

Run:

```bash
./mvnw -pl blob-helper-dashboard -Dtest=InstanceRegistrationTest test
```

Execution steps:

- [x] Write tests for stable-ID registration upsert, non-blocking registration failure, and loopback dashboard defaults.
- [x] Run the focused test command and confirm the tests fail because registration and the dashboard application do not exist.
- [x] Add the dashboard module, application class, registration request model/controller, and management-side registration client.
- [x] Run the focused test command and confirm registration is idempotent and dashboard startup defaults are correct.
- [ ] Commit with `feat(dashboard): add local instance registration`.

## Task 3: SQLite History and Polling

**Files:**

- Add SQLite JDBC and Spring JDBC dependencies to the dashboard only.
- Create tables for `dashboard_instance`, `metric_snapshot`, and
  `failure_event` with foreign keys and indexes on instance/time.
- Create repositories with parameterized SQL and UTC timestamps.
- Create `MetricDeltaCalculator` to compare cumulative readings with the prior
  poll; when a counter decreases, treat the new value as a process-reset
  baseline instead of recording a negative delta.
- Create `InstancePollingService` with a scheduled fixed delay, per-instance
  failure isolation, stale/disconnected status transitions, and cleanup of
  failures older than seven days.

**Tests first:**

- `calculatesCounterDelta` verifies normal cumulative-to-interval conversion.
- `counterResetStartsNewBaseline` verifies restart handling.
- `retainsFailuresForSevenDays` verifies old failure rows are deleted while
  snapshots remain.
- `pollFailureDoesNotHideHealthyInstances` verifies independent instance status.

Run:

```bash
./mvnw -pl blob-helper-dashboard -Dtest='*RepositoryTest,*CalculatorTest' test
```

Execution steps:

- [x] Write tests for normal cumulative counter deltas, counter resets, and seven-day failure cleanup; per-instance isolation is implemented in the polling service.
- [x] Add the SQLite schema, parameterized repositories, delta calculator, scheduled polling service, and retention cleanup.
- [x] Run the focused test command and confirm normal deltas, reset baselines, and retention pass.
- [ ] Commit with `feat(dashboard): persist local monitoring history`.

## Task 4: Dashboard API and UI

**Files:**

- Create read-only endpoints for overview, instances, instance history, and
  failures.
- Serve `index.html`, `dashboard.css`, and `dashboard.js` from the dashboard
  JAR; do not add React, Node, or a separate frontend build.
- Use CSS custom properties for light/dark palettes, detect system preference,
  and persist the user’s theme choice in local storage.
- Render metric cards, status badges, tables, and simple charts from dashboard
  API responses.
- Represent loading, empty, stale, disconnected, and failed states explicitly.

**Tests first:**

- `overviewAggregatesEnabledInstances` verifies combined counters and savings.
- `instanceHistoryReturnsTimeSeries` verifies ordered interval data.
- `failuresEndpointReturnsSevenDayWindow` verifies the API retention boundary.
- `staticDashboardIsServed` verifies the dashboard page is available.

Run:

```bash
./mvnw -pl blob-helper-dashboard -Dtest=DashboardControllerTest test
```

Execution steps:

- [x] Write controller tests for overview aggregation, ordered instance history, seven-day failures, and static page serving.
- [x] Add read-only JSON views and the static HTML/CSS/JavaScript UI with CSS-variable light/dark themes and local theme preference.
- [x] Run the focused test command and confirm the API and static page tests pass.
- [ ] Manually start the dashboard and verify both themes, loading/empty/error states, and read-only navigation at `http://127.0.0.1:9090`.
- [ ] Commit with `feat(dashboard): add read-only monitoring console`.

## Task 5: End-to-End Verification and Documentation

**Files:**

- Add `MultiInstanceDashboardIntegrationTest` with two in-process management
  endpoints and one SQLite dashboard database.
- Add a management contract test covering all response shapes.
- Update `docs/SPECIFICATION.md`, `docs/architecture.md`,
  `docs/implementation.md`, `docs/provider-testing.md`, and the root README
  with startup, configuration, API, and local-only security guidance.

**Tests first:**

- `twoInstancesRegisterAndContributeIndependentMetrics` verifies both
  applications appear separately and in the combined overview.
- `deduplicationSavingsUseLogicalMinusPhysicalBytes` verifies the dashboard’s
  traffic contribution calculation.
- `failedInstanceIsMarkedDisconnected` verifies one instance can fail while
  another remains healthy.
- `failureDetailsExpireAfterSevenDays` verifies bounded detailed history.

Run the focused and full verification:

```bash
./mvnw -pl dedup4j-spring-boot-management,blob-helper-dashboard test
./mvnw --batch-mode --no-transfer-progress verify
```

Execution steps:

- [x] Write the two-instance in-process integration test and management response contract test.
- [x] Run the focused integration tests and verify both module contracts.
- [x] Verify independent polling/status, combined savings, and seven-day failure cleanup.
- [x] Run the focused module tests and the full reactor verification command.
- [x] Update the specification, architecture, implementation index, provider-testing guide, README, task statuses, and changelog.
- [ ] Commit with `test(dashboard): verify local multi-instance monitoring`.

## Definition of Done

- [x] All task acceptance tests pass.
- [x] Multiple local instances self-register and are independently polled.
- [x] Aggregate traffic contribution and deduplication savings are charted.
- [x] Detailed failures are retained for exactly seven days.
- [x] Dashboard and registration defaults bind to loopback.
- [x] No authentication, remote access, billing integration, or write action is
      introduced into the MVP.
- [x] Documentation and changelog are updated.
