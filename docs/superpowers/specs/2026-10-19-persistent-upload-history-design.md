# Persistent upload history and failure reporting

## Why

dedup4j's dashboard numbers are meant to show users how much value the library has
delivered to their application since they adopted it. Today, five of them reset on every
restart, because they exist only as in-memory Micrometer counters:

- uploads
- duplicates
- new uploads
- logical bytes
- bytes avoided

Content count and physical bytes survive restarts, because they are read from the database.
Edem's analogy: a queue dashboard that only shows messages since the broker last restarted
says nothing about the work it has done.

Two related defects:

- An upload is counted *before* its bytes are written to storage. A failed upload therefore
  appears in the totals as a "new upload". This was observed on 2026-10-19, when an expired
  AWS session made an S3 write fail.
- "Recent failures" is always empty. `Dedup4jManagementSnapshot.FailureSource` is consumed
  by the management API and the embedded dashboard, but nothing implements it.

## Goals

1. All-time totals for uploads, duplicates, new uploads, logical bytes, bytes avoided and
   physical writes survive application restarts.
2. Totals belong to the **application's history dataset** (its database), not to one
   process. Replicas that share a database report the same totals. The standalone
   dashboard counts each dataset once.
3. Only completed uploads count. A failed attempt is excluded from every total.
4. Upload failures and physical-delete failures are persisted, and appear in "Recent
   failures" in all three places:
   - the management failures endpoint
   - the embedded dashboard
   - the standalone dashboard, through a real ingestion path
5. Reading totals stays O(1) at thousands of uploads per day, sustained for years.

## Non-goals

- **No backfill.** Totals start at zero when an application installs this version
  (Edem's decision).
- No removal of the existing Micrometer meters. They stay for Prometheus users and change
  only their timing (see "Write path").
- No new trend charts.

## Anti-goals (a failure even if it works)

- History that can be updated, deleted or truncated. Edem's standing rule: audit tables
  are immutable and append-only.
  - Enforced in the database.
  - Insert-only repositories and immutable entities in the app.
  - No maintenance or correction path. A mistake is fixed only by appending a compensating
    row that references the original, recorded at a new timestamp.
- Any total that can silently miss or double-count a committed upload. This includes:
  - concurrent transactions
  - the duplicate-race retry
  - replicas sharing a database
  - a standalone dashboard restart
- An audit table on a database where the rule cannot be enforced.

## Terms

| Term | Meaning |
|---|---|
| Upload | An upload request that completed successfully |
| Duplicate | An upload whose outcome was existing content |
| New upload | An upload whose outcome was new content (uploads − duplicates). It measures outcomes, not physical storage writes. |
| Physical write | An upload where *this request* performed a storage put. During an insert race, two requests can both write, and one then recovers as a duplicate. That recovered request is a duplicate and also a physical write. |
| Skipped physical writes | uploads − physical writes |
| Storage avoided (`avoidedBytes`) | Bytes that did not become an additional stored copy: the sum of the sizes of duplicate outcomes. A duplicate that lost an insert race still counts, even though its bytes were sent to storage once, because the same key was overwritten rather than duplicated. It measures capacity saved, not upload traffic saved. Dashboards label it "Storage avoided" and show this definition as help text. |

## Identities

- **Dataset identity.** A single row in `dedup4j_history_dataset`, holding a UUID
  `dataset_id` and `created_at`.
  - Created by a Liquibase changeset using the dbms-specific random-UUID function.
  - Never changes, and shared by every replica on that database.
  - The row is also the serialization point for the upload ledger (below).
- **Instance identity.** The existing `dedup4j.management.instance-id`. Stored on every
  history row for attribution only. Totals never group by it.

The management info and metrics responses add `datasetId`.

## Tables (new Liquibase changesets after `001-create-asset-content`, which is unchanged)

### `dedup4j_history_dataset`

| Column | Contents |
|---|---|
| `id` | Constant key, constrained to exactly one row |
| `dataset_id` | UUID |
| `created_at` | When the dataset was created |

### `dedup4j_upload_event`, the upload ledger

Each row carries signed contributions and the running totals that follow from them.

| Column | Contents |
|---|---|
| `sequence_no` | Primary key; gapless, assigned under the ledger lock |
| `dataset_id` | FK to the dataset table |
| `instance_id` | The instance that recorded the row |
| `recorded_at` | When the row was recorded |
| `asset_content_id` | The stored content |
| `hash_algorithm`, `content_hash`, `size_bytes` | Content identity |
| `upload_delta` | +1, or −1 on a compensating row |
| `duplicate_delta`, `physical_write_delta` | +1, 0 or −1 |
| `logical_bytes_delta`, `avoided_bytes_delta` | Signed byte contributions |
| `total_uploads`, `total_duplicates`, `total_physical_writes`, `total_logical_bytes`, `total_avoided_bytes` | Running totals after this row |
| `compensates_sequence_no` | Nullable FK to this table |

- A compensating row negates the original's deltas, references it, and gets its own
  `recorded_at`.
- Nothing in this scope writes compensating rows. The columns and the arithmetic exist and
  are tested.

### `dedup4j_failure_event`

| Column | Contents |
|---|---|
| `sequence_no` | Primary key; gapless, assigned under the dataset lock |
| `id` | UUID, unique; the external identity |
| `dataset_id` | FK to the dataset table |
| `instance_id` | The instance where the failure happened |
| `recorded_at` | When the failure happened |
| `operation` | `upload` or `delete` |
| `content_hash` | Nullable |
| `object_key` | Nullable |
| `message` | Bounded length |
| `compensates_failure_id` | Nullable FK to this table |

## Why a ledger instead of checkpoints

A time-boundary checkpoint can permanently miss an event that commits late with an
earlier timestamp. A safety margin only makes that less likely. The ledger removes the
problem by construction:

1. In the upload transaction, after the storage write succeeds and the content or
   reference-count change is made, the recorder runs
   `SELECT … FROM dedup4j_history_dataset FOR UPDATE`. The row lock is never followed by
   an update.
2. Holding that lock, it reads the row with the highest `sequence_no` and inserts the next
   one, with totals = previous totals + deltas.
3. The lock is held until commit.

Every insert is therefore serialized against the latest *committed* row, so no row can
ever be skipped. The `sequence_no` primary key is a second guard.

- **Reading totals** is one indexed lookup of the highest `sequence_no`. An empty ledger
  means zeros.
- **Cost.** Uploads serialize only for the few statements between the ledger insert and
  commit. That is negligible at the target of thousands per day; state it in the docs.
- **Failure log.** It uses the same mechanism: lock the dataset row, then insert
  `sequence_no` = highest + 1. Readers page failures by `sequence_no`, never by
  timestamp. A timestamp cursor can permanently skip a failure whose transaction commits
  after a later one; the sequence cannot, because assignment and commit both happen under
  the lock.
- **Required test.** Transaction A takes the ledger lock and delays its commit. Transaction
  B starts later and must wait. After both commit:
  - the sequence is gapless
  - the final totals equal the sum of all deltas
  - no event is missing

## Database enforcement

The history feature runs only on databases where the rule is enforced: **PostgreSQL and
H2**. Startup fails with a clear message on any other database while
`dedup4j.history.enabled` is true, which is the default. Setting it to `false` skips the
history changesets entirely. The dashboards then fall back to process-local counters and
label them as "since start".

### PostgreSQL

On all three tables:

- `BEFORE UPDATE OR DELETE … FOR EACH ROW` and `BEFORE TRUNCATE … FOR EACH STATEMENT`
  triggers raise an exception.

### H2

- `BEFORE UPDATE, DELETE` row triggers implemented with `org.h2.api.Trigger`. The trigger
  class lives in the starter, which takes H2 at `optional` scope.
- H2 triggers do not fire on `TRUNCATE`. H2 does reject `TRUNCATE` on any table referenced
  by a foreign key, including a self-reference. Verified on 2026-10-19 against H2 2.4.240:
  `Cannot truncate "PUBLIC.EV"`.
  - The dataset table is referenced by both event tables.
  - Each event table references itself through its compensation column.

  All three tables are therefore protected.

### Bypasses

These are the same class of bypass as PostgreSQL's `ALTER TABLE … DISABLE TRIGGER`. They
require schema-owner privileges and are out of scope:

- `SET REFERENTIAL_INTEGRITY FALSE`
- dropping the trigger

### Required tests

- H2: `UPDATE`, `DELETE` and `TRUNCATE` are rejected on all three tables.
- PostgreSQL: the same, using Testcontainers. These tests are required, not optional.

## Write path

Recording lives in `SpringTransactionalBlobDeduplicationService`. That wrapper owns the
transaction and the `DuplicateContentIdentityException` retry.

- **Success.** Append the ledger row inside the transaction that stores or retains the
  content. A retried upload that ends as a duplicate produces exactly one row, with
  `duplicate_delta` = 1, and `physical_write_delta` = 1 if this request wrote to storage.
  It produces no failure row.
- **Micrometer meters** increment only in an after-commit transaction synchronization,
  on both the first-attempt path and the retry path. Move the existing
  `metrics.recordUpload` calls out of `DefaultBlobDeduplicationService.store`.
- **Failure.** A failure is any exception that escapes the wrapper after retries.
  1. Append a failure row in a new transaction (`REQUIRES_NEW`).
  2. Rethrow the original exception unchanged.
  3. If recording the failure fails, log it and still rethrow the original.
- **Deletes.** Failure recording for deletes also lives in the wrapper, around `release`,
  with the same guard: record in `REQUIRES_NEW` after the release transaction has ended,
  and always rethrow the original exception, whether or not recording succeeds.
- **Entity immutability.** Use JPA-standard means, which keep `dedup4j-jpa`
  provider-neutral (no Hibernate annotations):
  - `@Column(updatable = false)` on every column
  - no setters
  - a `protected` no-arg constructor

## Read path

- **Starter.** A history reader returns:
  - `datasetId`
  - the latest ledger totals
  - failures since a cursor, paged by `(recorded_at, id)` with a bounded `limit`
- **Management module.** Implements `FailureSource` on top of the history reader (an
  adapter there avoids a dependency cycle).
  - `GET …/failures` gains `limit` (default and maximum bounded) and an `after` cursor.
  - The metrics response reads totals from the history reader.
- **Embedded dashboard.** `EmbeddedDashboardSnapshotService` reads totals from the history
  reader.
- **`physicalUploads` fix.** It is currently fed `skipped.physical.writes`. It becomes
  total physical writes.

## Standalone dashboard (`dedup4j-dashboard`)

### Datasets and totals

- Each polled instance reports `datasetId`. An instance on an older version without one is
  its own dataset, keyed by its instance id.
- Overview totals count each dataset once, using that dataset's most recent poll. This
  applies to all figures, including content count and physical bytes.
- Instance rows still show their dataset's figures, and are labelled with the dataset they
  share.

### Deltas for trends

- The first reading for a dataset after a dashboard restart is a zero-delta baseline.
- Deltas are computed per dataset, not per instance, so replicas never multiply trends.

### Failure ingestion

1. Poll `…/failures?afterSequence=<cursor>&limit=<n>` per dataset (from any one healthy
   instance of it). The order is ascending by `sequence_no` on every page, including the
   first. Keep paging until a page comes back short, with a per-cycle page cap; the next
   cycle continues from the cursor.
2. The cursor is kept per dataset in a dashboard table `failure_cursor`, so it survives
   dashboard restarts and is independent of retention deletes. With no cursor, start from
   `afterSequence=0`. Rows older than the retention window are skipped on insert, not
   requested by time.
3. Store rows keyed by `(dataset_id, failure_id)` with insert-or-ignore, so replicas
   exposing the same rows never duplicate them.
4. Instances on an older version without `afterSequence` support get one `since` request
   per cycle, deduplicated by the same key. This is best effort and documented.
5. The descending "recent failures" list used for display stays a separate query.

### Upgrading an existing dashboard database

Existing `metric_snapshot` and `failure_event` rows have no dataset.

- The migration sets their `dataset_id` to `instance:<instance_id>`. They were per-process
  counter deltas, so each one already belonged to exactly one instance, and summing them in
  trends stays correct.
- Dataset-based trends begin at the upgrade.
- `/instances/{id}/history` returns both the instance's legacy pseudo-dataset rows and its
  current dataset's rows, so existing history stays visible.

### The SQLite failure table holds two kinds of rows

Each row records its origin:

| Origin | What it is |
|---|---|
| `application` | Copies of rows whose authoritative record is the application's immutable `dedup4j_failure_event`. A disposable display cache with 7-day retention (Edem's decision). |
| `poll` | Dashboard-generated reachability failures. No upstream record exists. These are diagnostic, not audit data: the dashboard's own connectivity log, kept 7 days as today. |

Both are cleared by retention. The distinction is explicit in the schema and the docs.

## Constraints

- `001-create-asset-content` must not change.
- New tables follow the `dedup4j_` naming convention.
- After implementation, follow the repo's CLAUDE.md:
  - update the docs
  - append to `docs/changelog.md`
  - add an ADR to `docs/decisions.md` (durable append-only history replaces in-memory
    totals; history is restricted to PostgreSQL and H2)

## Left to the builder

- Package and class names
- Message length bound
- Default and maximum page sizes
- Per-cycle page cap
- Test layout. Coverage must include:
  - restart survival
  - a failed upload excluded from totals and present in failures
  - the duplicate-race retry, counted once with the correct physical-write flag
  - meters unchanged when commit fails
  - the delayed-commit ledger test
  - compensation arithmetic
  - trigger and `TRUNCATE` rejection on H2 and PostgreSQL
  - startup refusal on an unsupported database
  - a standalone dashboard with two replicas on one dataset: no double counting of totals,
    trends or failures
  - standalone dashboard restart: baseline delta, and failure cursor recovery
