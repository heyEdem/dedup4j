# Task 6.5: Add Structured Operational Logging

**Status:** Complete
**Source:** [PLAN-006](../../../implementation-plans/PLAN-006-reconciliation-observability.md)  
**ADR:** [ADR-003](../../../adrs/ADR-003-release-delete-and-reconciliation.md)

## Goal

Log operational decisions without exposing full content hashes by default.

## Files

- Modify: `dedup4j-spring-boot-starter/src/main/java/com/edem/dedup4j/service/DefaultBlobDeduplicationService.java`
- Create: `dedup4j-spring-boot-starter/src/test/java/com/edem/dedup4j/observability/Dedup4jLoggingTest.java`

## Steps

- [x] Log content id, provider, object key, duplicate/new decision, and hash prefix.
- [x] Do not add a separate full hash field by default.
- [x] Log failed physical deletes for later reconciliation.
- [x] Run `./mvnw -pl dedup4j-spring-boot-starter -Dtest=Dedup4jLoggingTest test`.

## Acceptance

- [x] Logs include hash prefix only as the explicit hash field by default.
- [x] Logs include enough context for failed delete reconciliation.
