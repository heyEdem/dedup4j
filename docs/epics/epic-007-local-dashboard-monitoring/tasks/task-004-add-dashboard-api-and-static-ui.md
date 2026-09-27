# Task 7.4: Add Dashboard API and Light/Dark Static UI

**Status:** Complete
**Source:** [PLAN-007](../../../implementation-plans/PLAN-007-local-dashboard-monitoring.md)
**ADR:** [ADR-005](../../../adrs/ADR-005-local-dashboard-pull-monitoring.md)

## Goal

Present current and historical monitoring information in a clean, read-only
RabbitMQ-style local console.

## Files

- Create: `dedup4j-dashboard/src/main/java/com/edem/dedup4j/dashboard/api/DashboardController.java`
- Create: `dedup4j-dashboard/src/main/java/com/edem/dedup4j/dashboard/api/DashboardView.java`
- Create: `dedup4j-dashboard/src/main/resources/static/index.html`
- Create: `dedup4j-dashboard/src/main/resources/static/css/dashboard.css`
- Create: `dedup4j-dashboard/src/main/resources/static/js/dashboard.js`
- Create: `dedup4j-dashboard/src/test/java/com/edem/dedup4j/dashboard/api/DashboardControllerTest.java`

## Acceptance

- [x] API exposes read-only overview, instances, metric history, and recent
      failures resources.
- [x] UI includes overview, instances, instance detail, failures, and settings
      views.
- [x] UI shows upload attempts, duplicate uploads, physical uploads, logical
      bytes, physical bytes, avoided bytes, provider health, and errors.
- [x] Light and dark themes work from the first release, with system preference
      detection and a manual toggle.
- [x] Empty, loading, stale, disconnected, and failed states are explicit.
- [x] No UI action mutates instance state or storage.
