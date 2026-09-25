# Task 6.4: Add Micrometer Metrics

**Status:** Complete
**Source:** [PLAN-006](../../../implementation-plans/PLAN-006-reconciliation-observability.md)  
**ADR:** [ADR-003](../../../adrs/ADR-003-release-delete-and-reconciliation.md)

## Goal

Expose metrics for upload volume, deduplication savings, storage latency, delete failures, and repairs.

## Files

- Create: `dedup4j-spring-boot-starter/src/main/java/com/edem/dedup4j/observability/Dedup4jMetrics.java`
- Create: `dedup4j-spring-boot-starter/src/test/java/com/edem/dedup4j/observability/Dedup4jMetricsTest.java`

## Steps

- [x] Add counters for uploads, duplicates, skipped physical writes, accepted bytes, and avoided bytes.
- [x] Add timers for hashing and storage writes.
- [x] Add counters for storage delete failures and repairs.
- [x] Run `./mvnw -pl dedup4j-spring-boot-starter -Dtest=Dedup4jMetricsTest test`.

## Acceptance

- [x] Duplicate upload increments duplicate and skipped-upload metrics.
- [x] Metrics are optional through normal Spring Boot/Micrometer behavior.
