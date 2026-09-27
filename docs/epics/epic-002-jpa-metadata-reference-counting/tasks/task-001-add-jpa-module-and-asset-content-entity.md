# Task 2.1: Add JPA Module and AssetContent Entity

**Status:** Complete
**Source:** [PLAN-002](../../../implementation-plans/PLAN-002-jpa-metadata-and-reference-counting.md)  
**ADRs:** [ADR-001](../../../adrs/ADR-001-content-identity-and-core-boundaries.md), [ADR-002](../../../adrs/ADR-002-deduplicated-upload-reference-counting.md)

## Goal

Create `dedup4j-jpa` and map physical blob metadata to `blob_asset_content`.

## Files

- Modify: `pom.xml`
- Create: `dedup4j-jpa/pom.xml`
- Create: `dedup4j-jpa/src/main/java/com/edem/dedup4j/jpa/AssetContent.java`
- Create: `dedup4j-jpa/src/test/java/com/edem/dedup4j/jpa/AssetContentMappingTest.java`
- Create: `dedup4j-jpa/src/test/resources/META-INF/persistence.xml`

## Steps

- [x] Add `dedup4j-jpa` to the Maven reactor.
- [x] Add JPA dependencies and a test database dependency.
- [x] Map `AssetContent` with UUID id, content identity, object location, content metadata, `refCount`, timestamps, and version.
- [x] Run `./mvnw -pl dedup4j-jpa test`.

## Acceptance

- [x] Table name is `blob_asset_content`.
- [x] Unique constraint covers `hash_algorithm`, `content_hash`, and `size_bytes`.
- [x] Indexes cover hash, object key, and reference count.
