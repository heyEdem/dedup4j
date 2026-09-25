# Task 2.3: Add Reference Retain and Release Service

**Status:** Complete
**Source:** [PLAN-002](../../../implementation-plans/PLAN-002-jpa-metadata-and-reference-counting.md)  
**ADR:** [ADR-003](../../../adrs/ADR-003-release-delete-and-reconciliation.md)

## Goal

Implement safe `ref_count` increment and decrement behavior.

## Files

- Create: `dedup4j-jpa/src/main/java/com/edem/dedup4j/jpa/ReferenceCountService.java`
- Create: `dedup4j-jpa/src/test/java/com/edem/dedup4j/jpa/ReferenceCountServiceTest.java`

## Steps

- [x] Add `retain(UUID assetContentId)`.
- [x] Add `release(UUID assetContentId)` with underflow protection.
- [x] Add tests for increment, decrement, and underflow failure.
- [x] Run focused `ReferenceCountServiceTest` verification with the reactor dependency included.

## Acceptance

- [x] Retain increments exactly once.
- [x] Release decrements exactly once.
- [x] `ref_count` never goes below zero.
