# Task 6.2: Implement Mismatch Reporting

**Status:** Complete
**Source:** [PLAN-006](../../../implementation-plans/PLAN-006-reconciliation-observability.md)  
**ADR:** [ADR-003](../../../adrs/ADR-003-release-delete-and-reconciliation.md)

## Goal

Compare stored `ref_count` values with application-provided logical counts.

## Files

- Create: `dedup4j-spring-boot-starter/src/main/java/com/edem/dedup4j/reconcile/ReconciliationService.java`
- Create: `dedup4j-spring-boot-starter/src/test/java/com/edem/dedup4j/reconcile/ReconciliationServiceTest.java`

## Steps

- [x] Write `reportsReferenceCountMismatch`.
- [x] Load physical content metadata.
- [x] Ask the application count source for expected counts.
- [x] Return mismatch reports without mutating the database.
- [x] Run `./mvnw -pl dedup4j-spring-boot-starter -Dtest=ReconciliationServiceTest test`.

## Acceptance

- [x] Reporting works with repair disabled.
- [x] Mismatches include asset content id, expected count, and actual count.
