# Task 3.5: Add Provider Auto-Configuration Validation

**Status:** Done  
**Source:** [PLAN-003](../../../implementation-plans/PLAN-003-spring-boot-starter-service-api.md)  
**ADR:** [ADR-004](../../../adrs/ADR-004-pluggable-storage-and-spring-boot-starter.md)

## Goal

Wire exactly one configured `BlobStorage` provider and fail clearly otherwise.

## Files

- Create: `dedup4j-spring-boot-starter/src/main/java/com/edem/dedup4j/autoconfigure/Dedup4jAutoConfiguration.java`
- Create: `dedup4j-spring-boot-starter/src/test/java/com/edem/dedup4j/autoconfigure/Dedup4jAutoConfigurationTest.java`

## Steps

- [x] Add conditional auto-configuration for a single `BlobStorage`.
- [x] Fail startup for unsupported provider names.
- [x] Fail startup for missing or ambiguous providers.
- [x] Run `./mvnw -pl dedup4j-spring-boot-starter -Dtest=Dedup4jAutoConfigurationTest test`.

## Acceptance

- [x] `provider=local` selects local storage when present.
- [x] Unsupported provider fails with a clear message.
- [x] Starter contains no AWS or Azure SDK implementation.
