# Task 5.3: Add Azure Storage Module

**Status:** Complete
**Source:** [PLAN-005](../../../implementation-plans/PLAN-005-s3-azure-storage-adapters.md)  
**ADR:** [ADR-004](../../../adrs/ADR-004-pluggable-storage-and-spring-boot-starter.md)

## Goal

Create the Azure Blob Storage provider module and configuration.

## Files

- Modify: `pom.xml`
- Create: `dedup4j-storage-azure/pom.xml`
- Create: `dedup4j-storage-azure/src/main/java/com/edem/dedup4j/storage/azure/AzureBlobStorageProperties.java`

## Steps

- [x] Add module to the reactor.
- [x] Add Azure Blob SDK dependency only in this module.
- [x] Add properties for container, connection string, endpoint, and account name.
- [x] Run `./mvnw -pl dedup4j-storage-azure test`.

## Acceptance

- [x] Azure SDK dependency is isolated to `dedup4j-storage-azure`.
- [x] Azure provider can be configured without changing core APIs.
