# Task 4.1: Add Local Storage Module

**Status:** Pending  
**Source:** [PLAN-004](../../../implementation-plans/PLAN-004-local-storage-adapter-and-integration-tests.md)  
**ADR:** [ADR-004](../../../adrs/ADR-004-pluggable-storage-and-spring-boot-starter.md)

## Goal

Create `dedup4j-storage-local` and its configuration model.

## Files

- Modify: `pom.xml`
- Create: `dedup4j-storage-local/pom.xml`
- Create: `dedup4j-storage-local/src/main/java/com/edem/dedup4j/storage/local/LocalBlobStorageProperties.java`

## Steps

- [ ] Add local storage module to the reactor.
- [ ] Depend on `dedup4j-core`.
- [ ] Add root-directory configuration.
- [ ] Run `./mvnw -pl dedup4j-storage-local test`.

## Acceptance

- [ ] Module compiles without cloud SDKs.
- [ ] Root directory is configurable.
