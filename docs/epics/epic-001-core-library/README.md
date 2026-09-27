# Epic 1: Core Library

**Status:** Complete  
**Progress:** 5/5
**Sources:** [ADR-001](../../adrs/ADR-001-content-identity-and-core-boundaries.md), [ADR-004](../../adrs/ADR-004-pluggable-storage-and-spring-boot-starter.md), [PLAN-001](../../implementation-plans/PLAN-001-core-library.md)

## Goal

Create the provider-neutral core module for hashing, object-key generation, storage contracts, and reusable command/result models.

## Tasks

- [x] 1.1 [Create Maven multi-module foundation](tasks/task-001-create-maven-multimodule-foundation.md)
- [x] 1.2 [Add streaming content hashing](tasks/task-002-add-streaming-content-hashing.md)
- [x] 1.3 [Add deterministic object key generation](tasks/task-003-add-deterministic-object-key-generation.md)
- [x] 1.4 [Add storage-neutral SPI and models](tasks/task-004-add-storage-neutral-spi-and-models.md)
- [x] 1.5 [Add core dependency boundary tests](tasks/task-005-add-core-dependency-boundary-tests.md)

## Done When

`dedup4j-core` compiles and passes tests without Spring, JPA, AWS, or Azure dependencies.
