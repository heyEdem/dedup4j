# Task 5.1: Add S3 Storage Module

**Status:** Complete
**Source:** [PLAN-005](../../../implementation-plans/PLAN-005-s3-azure-storage-adapters.md)  
**ADR:** [ADR-004](../../../adrs/ADR-004-pluggable-storage-and-spring-boot-starter.md)

## Goal

Create the S3 provider module and configuration.

## Files

- Modify: `pom.xml`
- Create: `dedup4j-storage-s3/pom.xml`
- Create: `dedup4j-storage-s3/src/main/java/com/edem/dedup4j/storage/s3/S3BlobStorageProperties.java`

## Steps

- [x] Add module to the reactor.
- [x] Add AWS SDK dependency only in this module.
- [x] Add properties for bucket, region, endpoint override, and path-style access.
- [x] Run `./mvnw -pl dedup4j-storage-s3 test`.

## Acceptance

- [x] AWS SDK dependency is isolated to `dedup4j-storage-s3`.
- [x] S3-compatible storage can be configured.
