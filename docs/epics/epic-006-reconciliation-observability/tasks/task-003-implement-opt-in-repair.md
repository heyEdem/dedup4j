# Task 6.3: Implement Opt-In Repair

**Status:** Complete
**Source:** [PLAN-006](../../../implementation-plans/PLAN-006-reconciliation-observability.md)  
**ADR:** [ADR-003](../../../adrs/ADR-003-release-delete-and-reconciliation.md)

## Goal

Repair reference count drift only when explicitly enabled.

## Files

- Modify: `dedup4j-spring-boot-starter/src/main/java/com/edem/dedup4j/reconcile/ReconciliationService.java`
- Modify: `dedup4j-spring-boot-starter/src/test/java/com/edem/dedup4j/reconcile/ReconciliationServiceTest.java`

## Steps

- [x] Add repair-enabled configuration.
- [x] Write `repairsOnlyWhenEnabled`.
- [x] Keep disabled mode read-only.
- [x] Update `ref_count` only through the JPA reference-count boundary.
- [x] Run `./mvnw -pl dedup4j-spring-boot-starter -Dtest=ReconciliationServiceTest test`.

## Acceptance

- [x] Repair disabled is the default.
- [x] Disabled repair performs no database mutation.
- [x] Enabled repair is explicit and auditable.
