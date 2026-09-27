# Task 7.3: Add SQLite Polling History and Failure Retention

**Status:** Complete
**Source:** [PLAN-007](../../../implementation-plans/PLAN-007-local-dashboard-monitoring.md)
**ADR:** [ADR-005](../../../adrs/ADR-005-local-dashboard-pull-monitoring.md)

## Goal

Poll registered instances, persist useful history in SQLite, and keep detailed
failure records bounded to seven days.

## Files

- Modify: `dedup4j-dashboard/pom.xml`
- Create: `dedup4j-dashboard/src/main/java/com/edem/dedup4j/dashboard/persistence/DashboardDatabase.java`
- Create: `dedup4j-dashboard/src/main/java/com/edem/dedup4j/dashboard/persistence/InstanceRepository.java`
- Create: `dedup4j-dashboard/src/main/java/com/edem/dedup4j/dashboard/persistence/MetricSnapshotRepository.java`
- Create: `dedup4j-dashboard/src/main/java/com/edem/dedup4j/dashboard/persistence/FailureEventRepository.java`
- Create: `dedup4j-dashboard/src/main/java/com/edem/dedup4j/dashboard/polling/InstancePollingService.java`
- Create: `dedup4j-dashboard/src/main/java/com/edem/dedup4j/dashboard/polling/MetricDeltaCalculator.java`
- Create: `dedup4j-dashboard/src/test/java/com/edem/dedup4j/dashboard/persistence/FailureEventRepositoryTest.java`
- Create: `dedup4j-dashboard/src/test/java/com/edem/dedup4j/dashboard/polling/MetricDeltaCalculatorTest.java`

## Acceptance

- [x] SQLite is the default dashboard database and requires no external server.
- [x] Registrations, metric snapshots, instance status, and failure details are
      persisted in separate tables.
- [x] Polling supports multiple instances independently and records last-seen
      and failure status per instance.
- [x] Cumulative instance counters are converted to interval deltas, including
      reset handling after an instance restart.
- [x] Logical bytes, physical bytes, avoided bytes, duplicate rate, and
      operation counts are available for trend charts.
- [x] Failure details older than seven days are deleted automatically; aggregate
      snapshots are not deleted by this cleanup.
