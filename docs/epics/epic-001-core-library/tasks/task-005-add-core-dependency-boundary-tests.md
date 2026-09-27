# Task 1.5: Add Core Dependency Boundary Tests

**Status:** Complete  
**Source:** [PLAN-001](../../../implementation-plans/PLAN-001-core-library.md)  
**ADRs:** [ADR-001](../../../adrs/ADR-001-content-identity-and-core-boundaries.md), [ADR-004](../../../adrs/ADR-004-pluggable-storage-and-spring-boot-starter.md)

## Goal

Protect `dedup4j-core` from Spring, JPA, AWS, and Azure dependencies.

## Files

- Modify: `dedup4j-core/pom.xml`
- Create: `dedup4j-core/src/test/java/com/edem/dedup4j/core/CoreModuleBoundaryTest.java`

## Steps

- [x] Add a dependency-boundary test using Maven dependency output or classpath inspection.
- [x] Assert no `org.springframework`, `jakarta.persistence`, `software.amazon.awssdk`, or `com.azure` artifacts are present.
- [x] Run `./mvnw -pl dedup4j-core test`.

## Acceptance

- [x] Boundary test fails if forbidden dependencies enter core.
- [x] Core remains framework-neutral.
