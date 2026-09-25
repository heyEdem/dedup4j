# Epic 7: Local Dashboard and Multi-Instance Monitoring

**Status:** Complete
**Progress:** 5/5
**Sources:** [ADR-005](../../adrs/ADR-005-local-dashboard-pull-monitoring.md), [PLAN-007](../../implementation-plans/PLAN-007-local-dashboard-monitoring.md)

## Goal

Provide a lightweight, read-only RabbitMQ-style dashboard that discovers and
monitors multiple local dedup4j instances, retains aggregate history, and
shows recent failures.

## Tasks

- [x] 7.1 [Add management module and local read-only API](tasks/task-001-add-management-module-and-local-read-only-api.md)
- [x] 7.2 [Add YAML self-registration and dashboard shell](tasks/task-002-add-yaml-self-registration-and-dashboard-shell.md)
- [x] 7.3 [Add SQLite polling history and failure retention](tasks/task-003-add-sqlite-polling-history-and-failure-retention.md)
- [x] 7.4 [Add dashboard API and light/dark static UI](tasks/task-004-add-dashboard-api-and-static-ui.md)
- [x] 7.5 [Add multi-instance end-to-end verification](tasks/task-005-add-multi-instance-end-to-end-verification.md)

## Done When

Multiple local dedup4j applications can self-register, appear in one
read-only dashboard, report current and historical traffic contribution, show
deduplication savings, and retain individual failures for seven days without
cloud billing integration or additional database infrastructure.
