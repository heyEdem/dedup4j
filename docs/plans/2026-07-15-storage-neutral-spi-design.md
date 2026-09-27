# Storage-Neutral SPI and Models Design

**Date:** 2026-07-15  
**Task:** 1.4 — Add storage-neutral SPI and models

## Goal

Define the framework- and provider-neutral Java contracts shared by the core service and every storage adapter.

## Chosen Approach

Use small Java 21 records with constructor validation, defensive metadata copies, and unchecked domain exceptions. This keeps the API compact while preventing invalid requests from reaching provider adapters. Provider SDK types and provider-specific settings remain outside `dedup4j-core`.

## API

- `BlobStorage` exposes `put(PutBlobRequest)`, `get(String)`, `delete(String)`, and `exists(String)`.
- `PutBlobRequest` carries the generated object key, content stream, size, optional content type and original filename, and metadata.
- `StoredBlob` reports the provider-neutral storage result: object key, provider, bucket/container, size, optional content type, checksum, and creation time.
- `BlobResource` carries a readable content stream and metadata. It implements `AutoCloseable`; closing the resource closes the stream.
- `StoreBlobCommand` is the application-facing input model and deliberately has no object key because keys are generated from content identity.
- `BlobReference` returns the asset-content ID, `ContentHash`, content type, provider, object key, and duplicate decision.

## Validation and Immutability

Required identifiers must be non-blank, streams and identity objects must be non-null, and byte sizes must be non-negative. Metadata maps are copied with `Map.copyOf`; a null metadata map is treated as empty. Optional descriptive fields remain nullable.

## Error Handling

All domain failures extend `Dedup4jException`. The initial categories are `BlobValidationException`, `BlobHashingException`, `BlobStorageException`, `ContentNotFoundException`, and `ReferenceCountUnderflowException`. They are unchecked so provider implementations can translate SDK failures without polluting the SPI with provider-specific checked exceptions.

## Testing

Tests will establish the API through red-green cycles and cover method signatures, required-field validation, defensive metadata copying, domain exception hierarchy/cause retention, and `BlobResource` stream closure. Existing core tests and the complete Maven reactor will be run before completion.
