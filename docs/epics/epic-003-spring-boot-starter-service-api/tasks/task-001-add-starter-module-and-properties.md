# Task 3.1: Add Starter Module and Properties

**Status:** Complete
**Source:** [PLAN-003](../../../implementation-plans/PLAN-003-spring-boot-starter-service-api.md)  
**ADR:** [ADR-004](../../../adrs/ADR-004-pluggable-storage-and-spring-boot-starter.md)

## Goal

Create the Spring Boot starter module and bind `blob-helper.*` configuration.

## Files

- Modify: `pom.xml`
- Create: `dedup4j-spring-boot-starter/pom.xml`
- Create: `dedup4j-spring-boot-starter/src/main/java/com/edem/dedup4j/autoconfigure/Dedup4jProperties.java`
- Create: `dedup4j-spring-boot-starter/src/test/java/com/edem/dedup4j/autoconfigure/Dedup4jPropertiesTest.java`

## Steps

- [x] Add starter module to the reactor.
- [x] Add properties for `storage`, `deduplication`, and `cleanup`.
- [x] Bind provider, key prefix, max upload size, strict content type validation, delete-on-zero-references, and reconciliation settings.
- [x] Run `./mvnw -pl dedup4j-spring-boot-starter test`.

## Acceptance

- [x] Properties bind from `blob-helper.*`.
- [x] Reconciliation is disabled by default.
- [x] No REST controllers are added.
