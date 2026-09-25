# Blob Helper Specification

## 1. Overview

Blob Helper is a reusable Java/Spring Boot library for deduplicated object
uploads. It prevents byte-identical files from being uploaded and stored more
than once while keeping application-level asset models flexible.

The design follows the article's core pattern:

- compute a strong content hash during upload
- store physical blob content separately from logical application assets
- enforce uniqueness on the content hash
- use reference counting to decide when physical storage can be deleted
- keep client-facing upload behavior normal and transparent

This project extends that design into reusable code for multiple projects and
multiple object storage providers.

## 2. Goals

- Provide a Spring Boot starter that consuming projects can add with minimal
  wiring.
- Support S3, Azure Blob Storage, and local filesystem storage.
- Keep the core deduplication logic independent of any object storage SDK.
- Allow future providers such as MinIO, Google Cloud Storage, Cloudflare R2, or
  custom internal storage.
- Let each consuming application own its logical asset domain model.
- Own only reusable physical content metadata and deduplication behavior.
- Handle concurrent uploads of the same file safely.
- Support transparent upload, download, and delete workflows.
- Provide reconciliation tools for reference count drift.
- Provide a lightweight local dashboard for inspecting the current application;
  optionally provide a separate dashboard for monitoring multiple Blob Helper
  instances and measuring deduplication savings over time.

## 3. Non-Goals

- This library will not own every application's logical asset table.
- This library will not provide image transformation, resizing, moderation, or
  CDN management in the first version.
- This library will not deduplicate visually similar images. Deduplication is
  byte-identical content deduplication.
- This library will not force one storage provider. Storage is pluggable.

## 4. Core Concepts

### Logical Asset

A logical asset is an application-owned record such as:

- profile photo
- product image
- uploaded document
- message attachment
- tenant media item

Logical assets belong to the consuming application because each project has
different ownership, permissions, lifecycle, and business fields.

### Asset Content

Asset content is the physical blob metadata owned by Blob Helper.

Many logical assets can point to one asset content row.

Example:

```text
profile_image_1 -> content_a
profile_image_2 -> content_a
document_9      -> content_b
```

### Content Hash

The first supported hash algorithm is SHA-256.

The content hash identifies byte-identical files. The uniqueness key should use
at least:

```text
hash_algorithm + hash + size_bytes
```

Including `size_bytes` provides an extra guard and improves lookup precision.

### Reference Count

`ref_count` tracks how many logical assets point to one physical content record.

- upload duplicate: increment `ref_count`
- delete logical asset: decrement `ref_count`
- `ref_count = 0`: physical object can be deleted

## 5. Module Design

### dedup4j-core

Framework-neutral code.

Responsibilities:

- hashing contracts
- SHA-256 streaming hasher
- storage-neutral request/response models
- `BlobStorage` SPI
- deduplication domain exceptions
- object key strategy contracts

No Spring, JPA, AWS, or Azure dependency should be required here.

### dedup4j-jpa

Persistence module for relational databases.

Responsibilities:

- `AssetContent` entity
- repository contracts
- optimistic or pessimistic locking support
- reference count operations
- reconciliation query support

### dedup4j-spring-boot-starter

Spring integration module.

Responsibilities:

- auto-configuration
- properties binding
- conditional provider wiring
- upload service bean
- delete/reference service bean
- optional scheduled reconciliation bean

### dedup4j-storage-s3

AWS S3 adapter.

Responsibilities:

- implement `BlobStorage`
- support bucket and key prefix configuration
- upload streams to S3
- delete objects from S3
- return object metadata and access information

### dedup4j-storage-azure

Azure Blob Storage adapter.

Responsibilities:

- implement `BlobStorage`
- support container and key prefix configuration
- upload streams to Azure Blob Storage
- delete blobs from Azure Blob Storage
- return object metadata and access information

### dedup4j-storage-local

Local filesystem adapter for tests and development.

Responsibilities:

- implement `BlobStorage`
- store files under a configured directory
- support deterministic integration tests without cloud credentials

### dedup4j-spring-boot-management

Optional Spring Boot management module for consuming applications.

Responsibilities:

- expose local, read-only health, metrics, information, and failure endpoints
- self-register the application with a local Blob Helper dashboard
- report provider-neutral operational data without exposing provider credentials

### blob-helper-dashboard

Standalone local fleet administration dashboard.

Responsibilities:

- accept self-registration from multiple local Blob Helper instances
- pull operational data from registered management endpoints
- retain aggregate traffic and deduplication history in SQLite
- retain detailed failures for seven days
- present a read-only light/dark web dashboard

### dedup4j-spring-boot-dashboard

Optional embedded dashboard starter for a single consuming Spring Boot
application.

Responsibilities:

- serve the read-only UI at `/dedup4j/dashboard` by default
- expose current-process overview, status, history, and failure views
- remain disabled only when `dedup4j.dashboard.enabled=false`
- avoid SQLite persistence, instance registration, and blob mutation

## 6. Storage Abstraction

The core storage contract should be small and provider-neutral.

```java
public interface BlobStorage {
    StoredBlob put(PutBlobRequest request);

    BlobResource get(String objectKey);

    void delete(String objectKey);

    boolean exists(String objectKey);
}
```

`PutBlobRequest` should include:

- object key
- content stream
- size in bytes
- content type
- original filename
- metadata map

`StoredBlob` should include:

- object key
- provider name
- bucket/container name
- size in bytes
- content type
- checksum if available
- created timestamp if available

Provider-specific settings must stay outside the core contract.

## 7. Data Model

### Asset Content Table

Recommended table name:

```text
blob_asset_content
```

Columns:

| Column | Type | Required | Notes |
|---|---:|---:|---|
| id | UUID | yes | primary key |
| hash_algorithm | varchar | yes | initially `SHA-256` |
| content_hash | varchar | yes | lowercase hex SHA-256 |
| size_bytes | bigint | yes | file size |
| object_key | varchar | yes | provider object key |
| storage_provider | varchar | yes | `s3`, `azure`, `local`, `custom` |
| bucket_or_container | varchar | yes | provider location |
| content_type | varchar | no | detected or supplied MIME type |
| original_extension | varchar | no | useful for object key generation |
| ref_count | bigint | yes | number of logical references |
| created_at | timestamp | yes | creation time |
| updated_at | timestamp | yes | last update time |

Unique constraint:

```text
unique(hash_algorithm, content_hash, size_bytes)
```

Indexes:

```text
idx_blob_asset_content_hash
idx_blob_asset_content_object_key
idx_blob_asset_content_ref_count
```

### Consuming Application Asset Table

The consuming app owns this table.

Example only:

```text
app_asset
  id
  owner_id
  asset_content_id
  filename
  content_type
  created_at
```

The app should store a foreign key or UUID reference to `blob_asset_content.id`.

## 8. Upload Flow

### New Content

```text
1. App receives upload.
2. Blob Helper validates size and optional content type.
3. Blob Helper streams bytes through SHA-256 hasher.
4. Blob Helper checks for existing AssetContent by hash + size.
5. No match is found.
6. Blob Helper creates an object key.
7. Blob Helper uploads bytes through BlobStorage.
8. Blob Helper inserts AssetContent with ref_count = 1.
9. App creates its logical asset pointing to AssetContent.
10. App returns its normal response.
```

### Duplicate Content

```text
1. App receives upload.
2. Blob Helper computes SHA-256 and size.
3. Existing AssetContent is found.
4. Blob Helper increments ref_count.
5. Blob Helper skips object storage upload.
6. App creates a new logical asset pointing to the existing content.
7. App returns its normal response.
```

## 9. Delete Flow

```text
1. App deletes or detaches its logical asset.
2. App calls Blob Helper with the referenced AssetContent id.
3. Blob Helper locks the AssetContent row.
4. Blob Helper decrements ref_count.
5. If ref_count remains above 0, no storage delete occurs.
6. If ref_count reaches 0, Blob Helper deletes the object from storage.
7. Blob Helper deletes or tombstones the AssetContent row.
```

Deletion should be idempotent at the storage adapter level. Missing objects
should be treated as already deleted unless strict mode is enabled.

## 10. Concurrency Design

Concurrent uploads of identical bytes are expected.

Required protections:

- database unique constraint on `hash_algorithm + content_hash + size_bytes`
- transactional lookup and insert
- retry on duplicate key violation
- row lock when incrementing or decrementing `ref_count`

Recommended new-content race handling:

```text
1. Two requests compute the same hash.
2. Both find no existing row.
3. One request inserts AssetContent successfully.
4. The second insert fails on the unique constraint.
5. The second request reloads the existing row and increments ref_count.
```

The storage upload ordering needs care. The first version should prefer a
deterministic object key based on hash so duplicate concurrent uploads converge
on the same object key.

Recommended key format:

```text
{prefix}/{hash_algorithm}/{first_two_hash_chars}/{content_hash}
```

Example:

```text
uploads/sha-256/a3/a3f1...
```

## 11. Configuration

Target Spring Boot configuration:

```yaml
blob-helper:
  storage:
    provider: s3
    key-prefix: uploads
  deduplication:
    hash-algorithm: SHA-256
    max-upload-size: 25MB
    strict-content-type-validation: false
  cleanup:
    delete-physical-on-zero-references: true
    reconciliation-enabled: false
```

S3:

```yaml
blob-helper:
  storage:
    provider: s3
    s3:
      bucket: my-bucket
      region: eu-west-1
```

Azure:

```yaml
blob-helper:
  storage:
    provider: azure
    azure:
      container: my-container
      connection-string: ${AZURE_STORAGE_CONNECTION_STRING}
```

Local:

```yaml
blob-helper:
  storage:
    provider: local
    local:
      root-directory: ./.blob-helper
```

## 12. Public Service API

The starter should expose application-facing services instead of controllers.
Each project can decide its own REST shape.

```java
public interface BlobDeduplicationService {
    BlobReference store(StoreBlobCommand command);

    void retain(UUID assetContentId);

    void release(UUID assetContentId);

    BlobResource get(UUID assetContentId);
}
```

`StoreBlobCommand` should include:

- input stream or multipart file adapter
- filename
- content type
- size
- metadata

`BlobReference` should include:

- asset content id
- content hash
- size
- content type
- storage provider
- object key
- duplicate flag

## 13. Error Handling

Expected exception categories:

- upload validation failure
- unsupported storage provider
- storage write failure
- storage delete failure
- content metadata persistence failure
- hash computation failure
- reference count underflow
- content not found

Storage failures after database changes need explicit handling. The first version
should keep operations transactional where possible and log failed physical
deletes for later reconciliation.

## 14. Reconciliation

Reference counts can drift if consuming apps fail after partial operations.

The library should provide a reconciliation service that can:

- count logical references using an app-provided callback or query adapter
- compare expected count with `blob_asset_content.ref_count`
- report mismatches
- optionally repair mismatches
- find zero-reference content
- verify object-store existence

The first implementation can expose the service without enabling scheduled
repairs by default.

## 14.1 Dashboard Monitoring

The optional local dashboard must not access consuming application databases or
object stores directly. Instances self-register with their instance ID, display
name, advertised management URL, provider name, and API version. The dashboard
polls each instance independently and marks only the affected instance stale or
disconnected when a poll fails.

The dashboard tracks Blob Helper's traffic contribution, not provider billing:

- upload attempts and duplicate uploads
- physical uploads and bytes written to the configured provider
- logical bytes accepted and avoided bytes from deduplication
- downloads, deletes, operation failures, and latency summaries

Successful operations are represented by aggregate counters. Detailed failure
records are retained for seven days; aggregate metric history is retained
independently. The dashboard is read-only and cannot delete blobs or repair
reference counts.

## 15. Security and Validation

The library should support:

- maximum upload size
- optional content type allowlist
- filename sanitization for metadata only
- generated object keys instead of user-controlled keys
- no public URL generation by default
- provider credentials supplied through normal Spring configuration

Hashing is for deduplication, not authorization. Applications remain responsible
for access control around logical assets.

## 16. Observability

Metrics should be designed around operational value:

- total uploads
- duplicate uploads
- physical uploads skipped
- bytes accepted
- bytes avoided
- storage upload latency
- hash computation latency
- storage delete failures
- reference count repairs

Logs should include:

- asset content id
- storage provider
- object key
- hash prefix only, not necessarily full hash
- duplicate/new decision

The optional dashboard provides the operator-facing view for these metrics and
failure signals. It is local-only in the first version and stores its own
registry and history in SQLite.

## 17. Testing Strategy

### Unit Tests

- SHA-256 hash computation
- deterministic object key generation
- duplicate detection logic
- reference count increment/decrement
- underflow protection
- provider selection

### Integration Tests

- upload new content
- upload duplicate content
- delete one of many references
- delete final reference
- concurrent duplicate upload
- local filesystem storage adapter
- JPA unique constraint and locking behavior

### Provider Tests

S3 and Azure tests should be separated from normal unit tests because they need
external services or containers/emulators.

### Dashboard Tests

- management endpoint contract tests
- self-registration and idempotent instance upsert
- multi-instance polling and independent failure status
- cumulative counter delta and process-reset handling
- SQLite metric persistence and seven-day failure retention
- read-only dashboard API and static UI smoke coverage
- embedded dashboard auto-configuration, current-process snapshots, and
  custom-base-path resource coverage

## 18. Implementation Phases

### Phase 1: Documentation and Project Shape

- root README
- specification
- Maven multi-module structure plan

### Phase 2: Core Library

- hashing API
- SHA-256 streaming implementation
- storage SPI
- object key strategy
- command/result models

### Phase 3: JPA Metadata

- `AssetContent` entity
- repository
- reference count service
- duplicate-key retry handling

### Phase 4: Spring Boot Starter

- properties
- auto-configuration
- upload service
- delete/release service
- local adapter default for tests

### Phase 5: Storage Providers

- S3 adapter
- Azure adapter
- provider-specific configuration
- provider integration tests

### Phase 6: Operational Tools

- reconciliation service
- metrics
- structured logs
- cleanup reporting

### Phase 7: Local Dashboard and Multi-Instance Monitoring

- optional local management API and YAML self-registration
- standalone pull-based dashboard
- SQLite aggregate metric history
- seven-day detailed failure history
- read-only light/dark dashboard UI

The local monitoring flow is verified with two in-process management endpoints,
independent failure status, aggregate metric contribution, and seven-day
detailed failure retention. Verification uses temporary SQLite storage and no
cloud credentials.

## 19. Open Questions

- Should zero-reference content be deleted immediately or tombstoned first?
- Should signed URL generation be included or left to applications?
- Should the first release support database migrations through Flyway/Liquibase?
- Should `ref_count` be authoritative, or should reconciliation be required for
  production usage?
- Should provider modules support checksums from object storage APIs in addition
  to SHA-256?
- Should remote dashboard access and authentication be added after the
  local-only MVP?

## 20. Success Criteria

The project is successful when another Spring Boot app can:

1. add Blob Helper dependencies
2. configure `dedup4j.storage.provider`
3. call one upload service
4. store its own logical asset pointing to `AssetContent`
5. avoid re-uploading identical bytes
6. safely delete physical objects only after the last reference is gone
7. switch from S3 to Azure by changing dependency and configuration, not business
   logic
8. start a local dashboard, see multiple instances self-register, and inspect
   historical traffic contribution and deduplication savings
