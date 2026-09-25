# Task 6.1: Add Reconciliation Contracts

**Status:** Complete
**Source:** [PLAN-006](../../../implementation-plans/PLAN-006-reconciliation-observability.md)  
**ADR:** [ADR-003](../../../adrs/ADR-003-release-delete-and-reconciliation.md)

## Goal

Define how consuming applications report logical reference counts to dedup4j.

## Files

- Create: `dedup4j-spring-boot-starter/src/main/java/com/edem/dedup4j/reconcile/LogicalReferenceCountSource.java`
- Create: `dedup4j-spring-boot-starter/src/main/java/com/edem/dedup4j/reconcile/ReconciliationReport.java`
- Create: `dedup4j-spring-boot-starter/src/main/java/com/edem/dedup4j/reconcile/ReconciliationMismatch.java`

## Steps

- [x] Add callback/query adapter interface for app-owned logical assets.
- [x] Add report and mismatch records.
- [x] Keep repair commands separate from report generation.
- [x] Run `./mvnw -pl dedup4j-spring-boot-starter test`.

## Acceptance

- [x] No application logical schema is assumed.
- [x] Reconciliation can report expected and actual counts.
