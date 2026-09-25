# Reference Retain and Release Design

**Date:** 2026-08-20
**Status:** Approved
**Scope:** `dedup4j-jpa` reference-count mutation service

## Goal

Provide a lock-aware service that increments and decrements `AssetContent.refCount` exactly once while rejecting missing content and reference-count underflow.

## Design

`ReferenceCountService` depends on `AssetContentRepository`. Both `retain(UUID)` and `release(UUID)` load the entity through `findByIdForUpdate`, preserving the repository's caller-owned transaction and pessimistic-lock contract. A missing identifier raises `ContentNotFoundException`.

The entity keeps its public API read-only for `refCount` and exposes package-private mutation methods used by the service. `release` checks for zero before decrementing and raises `ReferenceCountUnderflowException`; it never persists a negative count.

Physical storage deletion is intentionally excluded. Release orchestration and storage interaction belong to the later service layer described by ADR-003.

## Testing

An integration-style JPA test uses the existing Hibernate/H2 persistence unit to verify:

- retaining an existing row increments its count once;
- releasing a row decrements its count once;
- releasing a zero-count row throws the underflow exception and leaves the count unchanged;
- missing identifiers are reported as missing content.

