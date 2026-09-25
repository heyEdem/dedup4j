# Blob Helper

Blob Helper is a reusable Spring Boot library for deduplicated object uploads.
It stores identical file bytes once, while allowing each application to keep its
own logical asset records.

The first target use case is image upload deduplication like the design
described in the Medium article, but the project is storage-neutral. S3, Azure
Blob Storage, local filesystem storage, MinIO, Google Cloud Storage, or any
custom object store can be supported through the same storage adapter contract.

## Problem

Applications often upload the same file many times:

- users re-upload the same profile image
- multiple records attach the same document
- imports contain repeated images
- retries create duplicate object-store data

Without deduplication, each upload becomes a new object-store write and a new
stored object, even when the bytes are identical.

Blob Helper solves this by separating:

- logical assets owned by the consuming application
- physical blob content owned by Blob Helper

Many logical assets can point to one physical content record.

## Modules

```text
dedup4j-core
blob-helper-jpa
blob-helper-spring-boot-starter
blob-helper-storage-s3
blob-helper-storage-azure
blob-helper-spring-boot-management  (optional local management API)
blob-helper-spring-boot-dashboard   (optional embedded read-only dashboard)
blob-helper-spring-boot-observability (optional embedded management + dashboard)
blob-helper-dashboard                (standalone local monitoring console)
blob-helper-storage-local
```

`dedup4j-core` contains hashing, deduplication contracts, and storage-neutral
interfaces. Storage providers live in separate adapter modules, which are
included transitively by the standard Spring Boot starter.

For a consumer application, add the single starter dependency:

```xml
<dependency>
  <groupId>com.edem</groupId>
  <artifactId>blob-helper-spring-boot-starter</artifactId>
  <version>0.0.1-SNAPSHOT</version>
</dependency>
```

The starter supplies the local, S3, and Azure adapters. Their provider SDKs
remain declared and versioned only in the corresponding adapter modules. The
management API, embedded dashboard, and standalone dashboard are separate
optional artifacts and are not part of the generic upload starter.

For one dependency that supplies the embedded single-application observability
pieces, add:

```xml
<dependency>
  <groupId>com.edem</groupId>
  <artifactId>blob-helper-spring-boot-observability</artifactId>
  <version>${blob-helper.version}</version>
</dependency>
```

This aggregate installs the embedded dashboard and makes the management module
available. Management remains an explicit choice and is not exposed until the
consumer enables it:

```yaml
blob-helper:
  management:
    enabled: true
```

Select the storage provider in application configuration. For local storage:

```yaml
blob-helper:
  storage:
    provider: local
    local:
      root-directory: ./blobs
```

For S3, set `blob-helper.storage.provider=s3` and
`blob-helper.storage.s3.bucket`. AWS's standard region and credential chains
apply; `storage.s3.region`, `storage.s3.endpoint`, and
`storage.s3.path-style` are optional overrides for AWS or S3-compatible stores.
For Azure, set `storage.provider=azure`, `storage.azure.container`, and
`storage.azure.connection-string` or `storage.azure.endpoint` under
`blob-helper`.

The starter reuses an application `S3Client` or `BlobContainerClient` bean
before creating a default client. An application `BlobStorage` bean replaces
the provider defaults entirely. A supported provider selection is required
even with custom storage, and multiple storage beans fail startup. Startup
constructs clients without contacting storage; successful startup does not
verify cloud access.

The starter uses the application's `DataSource`, JPA entity manager, and Spring
transaction manager, and automatically supplies `BlobDeduplicationService`
and its internal collaborators. No Blob Helper configuration class is needed.
Each collaborator can be replaced with an application bean of the same type.
The consumer supplies its database driver and connection configuration; H2 is
not a runtime dependency of the starter.

`blob-helper.persistence.initialize-schema` controls the packaged Liquibase
migration:

| Mode | Behavior |
|---|---|
| `embedded` (default) | Initialize supported embedded databases; validate existing schema on external databases. |
| `always` | Explicitly authorize initialization on the consumer database. |
| `never` | Validate existing schema without running Blob Helper migrations. |

The migration is packaged at
`classpath:db/blob-helper/db.changelog-master.yaml`. It creates
`blob_helper_asset_content` and uses separate
`BLOB_HELPER_DATABASE_CHANGELOG` / `BLOB_HELPER_DATABASE_CHANGELOG_LOCK`
tracking tables. Missing schema causes a startup error with migration guidance.
The initial changelog does not transfer data from the earlier
`blob_asset_content` table; existing installations must migrate that metadata
as part of their database rollout.

The auto-configured service runs each operation in a new Spring metadata
transaction. A concurrent identity conflict rolls back before a fresh
transaction retains the winning row, without replaying the input stream or
writing storage again during recovery. These transactions do not include the
application's logical records or make object storage and the database atomic.

For common Spring upload flows, inject the auto-configured `BlobHelper` facade:

```java
public String uploadImage(MultipartFile file) {
    BlobReference stored = blobHelper.store(file);
    BlobLocation location = stored.location();
    uploadRepository.save(new Upload(location.objectKey(), stored.assetContentId()));
    return publicUrlMapper.toUrl(location);
}
```

`blobHelper.store` is the only physical upload call; the application must not
call `S3Client.putObject` or another provider SDK afterward. The application
creates its logical row for every successful call, including duplicates, and
may return its own URL, DTO, ID, `BlobReference`, or empty response. A stable
`BlobLocation` is provider-neutral storage identity, not an access URL; the
application owns public URL and presigned URL creation.

The facade also accepts `Path`, `byte[]`, and described `InputStream` sources.
`storeAll(MultipartFile[])` processes sequentially and returns one ordered
success or failure outcome for every input, allowing later uploads to continue
after an individual failure without claiming all-or-nothing semantics.

The optional management module exposes local read-only operational data and
self-registers instances with the standalone dashboard. The dashboard polls
multiple local instances and stores aggregate history in
SQLite; it does not manage blob bytes or provider credentials.

For a single Spring Boot application, the
`blob-helper-spring-boot-observability` aggregate supplies the embedded current-
application UI/API; open `http://localhost:8080/blob-helper/dashboard`.
Embedded dashboard mode is enabled by default and can be disabled with:

```yaml
blob-helper:
  dashboard:
    enabled: false
```

`blob-helper-spring-boot-observability` is the embedded current-application
UI/API. Use the separate `blob-helper-dashboard` application when you need
multi-instance polling and SQLite history.

## High-Level Flow

```text
Upload file
  -> stream through SHA-256 hasher
  -> look up existing content by hash and size
  -> if found, increment reference count and skip storage upload
  -> if not found, upload through configured storage adapter
  -> return a normal logical asset response
```

Delete flow:

```text
Delete logical asset
  -> decrement content reference count
  -> delete the physical object only when no assets reference it
```

## Documentation

- [Project Specification](docs/SPECIFICATION.md)
- [Task Index](docs/taskindex.md)
- [Docs Index](docs/README.md)

## Status

Phases 1–5 are complete: the core library, JPA metadata and reference counting,
Spring Boot starter, local storage, S3, and Azure adapters are implemented and
verified. Generic starter packaging and dependency-governance safeguards are
also in place. The local dashboard and multi-instance monitoring implementation
is complete; see [Epic 7](docs/epics/epic-007-local-dashboard-monitoring/README.md).
