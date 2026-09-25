# ADR-008: Consumer Database and Managed Schema

**Date:** 2026-09-03  
**Status:** Accepted  
**Deciders:** Edem and project maintainers

## Context

The current JPA module expects callers to create repositories, services, entity managers, and resource-local transactions. A Spring Boot consumer should receive a working service graph automatically. Deduplication metadata must also be shared across application instances, while production applications need control over database schema mutation.

## Decision

Blob Helper reuses the consuming application's `DataSource`, JPA `EntityManager`, and `PlatformTransactionManager`. The starter auto-discovers Blob Helper entities and conditionally registers repository, mutation, reference-count, metrics, transaction, and deduplication beans.

Public Blob Helper operations own Spring-managed metadata transactions. Each batch item uses an independent metadata transaction so one failure does not hide completed outcomes. Duplicate-key retry occurs across transaction boundaries and never manipulates `EntityTransaction` directly.

Blob Helper stores metadata in clearly prefixed tables and ships versioned schema resources. `blob-helper.persistence.initialize-schema` supports `embedded` (default), `always`, and `never`. External databases are mutated only by `always`. SQLite is deferred as an optional single-instance adapter.

## Invariants (from Q2)

- [ ] All application instances use one authoritative metadata database.
- [ ] Default beans back off individually when application beans exist.
- [ ] No Spring shared `EntityManager` is asked for a resource-local transaction.
- [ ] Concurrent duplicates converge on one row with one reference per successful call.
- [ ] `embedded`, `always`, and `never` enforce their documented mutation policies.
- [ ] Missing schema fails startup with actionable migration guidance.
- [ ] No consuming-application logical asset table is assumed or created.

## Architectural Ownership (from Q3)

| Concern | Owner |
|---|---|
| Entity and lock-aware repository behavior | `dedup4j-jpa` |
| Spring bean and transaction orchestration | `blob-helper-spring-boot-starter` |
| Schema properties, initialization, validation | starter persistence auto-configuration |
| Versioned schema resources | starter `src/main/resources/db/blob-helper` |
| Logical upload records | consuming application |

**Explicitly excluded layers:** storage adapters, controllers, entity callbacks, standalone dashboard persistence.

## Consequences

**Positive:**
- Consumers need no Blob Helper configuration class.
- Metadata uses the application's established database operations and security.
- Schema mutation is easy in development and explicit in production.

**Negative / Trade-offs:**
- The starter requires a functioning application JPA/DataSource setup.
- Per-operation metadata transactions cannot atomically include physical object storage.
- Schema resources become a release compatibility contract.

**Risks if violated:**
- Per-instance metadata could defeat deduplication and reference accuracy.
- Unsafe transaction retry could leave a transaction rollback-only.
- Silent production DDL could violate deployment controls.

## Rejected Alternatives

### Alternative A: Private SQLite metadata by default
- Why considered: lightweight zero-configuration persistence.
- Why rejected: separate files fragment metadata across instances and add independent backup, volume, and transaction concerns.

### Alternative B: Caller-owned manual bean graph
- Why considered: maximum framework flexibility.
- Why rejected: violates the zero-boilerplate Spring Boot outcome and duplicates fragile setup.

## Related

- ADR-002
- ADR-003
- Implementation Plan: PLAN-011
