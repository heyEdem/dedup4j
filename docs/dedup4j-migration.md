# dedup4j migration status

## Completed consumer-facing rename

Keep Maven group `com.edem` and version `0.0.1-SNAPSHOT`. Replace the former
artifact prefix `blob-helper` with `dedup4j`, including the parent and all ten
modules. Replace Java package prefix `com.edem.blobhelper` with
`com.edem.dedup4j`. The convenience facade is `Dedup4j`, its implementation is
`DefaultDedup4j`, and the root domain exception is `Dedup4jException`.
`BlobStorage`, `BlobReference`, and `BlobDeduplicationService` retain their names
and contracts.

Replace configuration prefix `blob-helper` with `dedup4j`. This includes storage,
deduplication, cleanup, persistence initialization, management, dashboard, and
dashboard-registration settings. The schema initialization modes remain
`embedded`, `always`, and `never`.

| Surface | Previous value | Current value |
|---|---|---|
| Meter prefix | `blob.helper.` | `dedup4j.` |
| Default management route | `/blob-helper/management` | `/dedup4j/management` |
| Default embedded UI route | `/blob-helper/dashboard` | `/dedup4j/dashboard` |
| Embedded static resources | `static/blob-helper/dashboard` | `static/dedup4j/dashboard` |

Update metric queries and scraping configuration. Custom route settings remain
supported. Management remains opt-in; the embedded dashboard remains enabled by
default when installed. Keep explicit registration UUIDs stable. If an instance
previously relied on a derived identity, configure its existing UUID explicitly
before changing its name or advertised URL.

## Persistence decision still required

**No application-schema migration has been performed.** Current code retains the
following identifiers and defaults pending Edem's compatibility decision:

| Current identifier/default | Planned target |
|---|---|
| `blob_helper_asset_content` | `dedup4j_asset_content` |
| `pk_blob_helper_asset_content` | `pk_dedup4j_asset_content` |
| `uk_blob_helper_asset_content_identity` | `uk_dedup4j_asset_content_identity` |
| `idx_blob_helper_asset_content_hash` | `idx_dedup4j_asset_content_hash` |
| `idx_blob_helper_asset_content_object_key` | `idx_dedup4j_asset_content_object_key` |
| `idx_blob_helper_asset_content_ref_count` | `idx_dedup4j_asset_content_ref_count` |
| `BLOB_HELPER_DATABASE_CHANGELOG` | `DEDUP4J_DATABASE_CHANGELOG` |
| `BLOB_HELPER_DATABASE_CHANGELOG_LOCK` | `DEDUP4J_DATABASE_CHANGELOG_LOCK` |
| `db/blob-helper/` | `db/dedup4j/` |
| `blob-helper-storage` | `dedup4j-storage` |
| `blob-helper-dashboard.sqlite` | `dedup4j-dashboard.sqlite` |

These are proposed mappings, not an executable upgrade procedure. The applied
changeset identity (`001-create-asset-content`, author `blob-helper`, and its
recorded resource path) is unchanged. Do not edit applied checksums or rename
Liquibase tracking tables without an approved migration procedure. The older
historical `blob_asset_content` schema also has no automatic upgrade path.

- **Fresh release:** consistently rename fresh schema resources and mappings,
  document that existing application schemas need a separate migration, and
  preserve original local files.
- **Supported upgrade:** implement and test an explicit migration against a copy,
  preserving IDs, reference counts, hashes, sizes, storage locations, and readback;
  verify restart does not reapply it.

Hash-derived object keys must remain unchanged under either policy. A changed
local storage default must not silently redirect an existing application to an
empty directory. Until a validated copy is selected, explicitly configure the
existing absolute root with `dedup4j.storage.local.root-directory`.

## Preserved local fleet history

The original files remain in the former module directory, relative to the checkout:

- `blob-helper-dashboard/blob-helper-dashboard.sqlite`: integrity check passed;
  one instance, 647 snapshots, six failures.
- `blob-helper-dashboard/blob-helper-dashboard.sqlite.backup`: integrity check
  passed; one instance, zero snapshots, 108 failures.

These counts were read on 2026-09-25 without modifying either file. Runtime data,
SQLite sidecars, and backups are narrowly ignored by Git. The directory is no
longer a Maven module. To read the existing database from the renamed application,
set `dedup4j.dashboard.database-path` to its absolute path; a relative filename is
resolved against the process working directory.

For a future copy migration, stop the dashboard, preserve a backup, use SQLite's
backup mechanism so committed WAL contents are included, and compare all rows
and UUIDs in the copy. Keep the source and its backup. Inspect each registration's
advertised URL; only change the default management suffix for instances that have
actually moved to the new endpoint, preserving custom paths and explicit UUIDs.
Starting the dashboard can poll instances and expire old failure records, so
validate copied history before startup. No copied database has been activated yet.

## Verification and handoff

Each module has its own commit with a passing clean reactor check. The current
reactor passes 190 tests. A packaged standalone instance, using temporary SQLite
and an unused loopback port, served HTML, JavaScript, CSS, and all four read API
routes successfully. Its history route now explicitly binds the UUID path
parameter, with HTTP-level regression coverage. The smoke process was stopped.

Rendered UI inspection is pending because no browser was connected. The legacy
root application and test were renamed and inspected; parent `pom` packaging
does not compile them.

Edem owns the GitHub repository and checkout-root rename. Keep the current remote
until the new repository name exists, then update origin and verify from the new
checkout root. Existing Git history is unchanged.
