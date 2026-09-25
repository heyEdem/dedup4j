# dedup4j Rename Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Edem explicitly requested a Luna high-reasoning audit; implementation delegation is not implied.

**Goal:** Rename the whole library to `dedup4j`, including documentation, build artifacts, Java names, resource paths, and runtime branding, with a green checkpoint and logical commit after each stage.

**Architecture:** Keep the existing ten-module architecture and behavior. Change coupled identifiers and their consumers in one stage. Treat persisted names and locations as a migration concern, not a text replacement.

**Tech Stack:** Java 21, Maven, Spring Boot 4.1.1, JPA, Liquibase, Micrometer, SQLite, static HTML/CSS/JavaScript.

**Spec:** The naming contract and acceptance criteria below capture Edem's September 25 request; `docs/architecture.md` and `docs/implementation.md` describe existing behavior.

## Naming contract and constraints

| Existing form | Target |
|---|---|
| Product display name | `dedup4j` |
| `blob-helper` artifact/module/path prefix | `dedup4j` |
| `com.edem.blobhelper` package | `com.edem.dedup4j` |
| `BlobHelper` class-name segment | `Dedup4j` |
| `blobHelper` variable/bean segment | `dedup4j` |
| `blob_helper` SQL/identifier prefix | `dedup4j` |
| `BLOB_HELPER` identifier prefix | `DEDUP4J` |
| `blob.helper` metric prefix | `dedup4j` |

- Preserve Maven group `com.edem`, versions, module boundaries, and domain names such as `BlobStorage`, `BlobReference`, and `BlobDeduplicationService`.
- Include tests, root legacy sources, hidden project configuration, documentation history in the working tree, and untracked authored documentation. Do not rewrite Git history or `.git` internals.
- Existing untracked documentation belongs to Edem. Review and stage only deliberate changes; never bulk-add SQLite files, backups, or `.DS_Store`.
- Edem owns the remote repository rename and checkout-root rename. Schedule those after internal changes are verified.
- No data deletion. Pending Edem's fresh-release versus upgrade choice, plan for preservation. Existing database migrations may require narrowly documented legacy identifiers; remove all other old branding. A literal zero-occurrence policy and automatic legacy-schema discovery are incompatible without an external migration step.
- This document's old-to-new mappings are intentional audit references. At final cleanup, retain them only in migration documentation if approved, or replace this completed plan's old-name examples with target-only descriptions.

## Review focus

1. Spring discovery can fail even when compilation succeeds: update `AutoConfiguration.imports`, entity packages, reflective names, and bean references together (stage 2).
2. Metrics can silently become zero: producers, management readers, and embedded dashboard readers must use the same names (stage 3).
3. A new database/storage default can hide existing data: validate migrated copies and unchanged content IDs, object keys, and reference counts (stage 4).
4. Resource relocation can serve broken pages: request the embedded UI, its assets, and APIs under default and custom base paths (stage 3).
5. Changed registration names can generate new UUIDs: preserve explicit instance IDs when migrating persisted fleet registrations (stages 3–4).

## Baseline and shared checkpoint

### Luna high-reasoning audit results

Read-only audit found 243 of 271 tracked files with old-name variants (2,193 matching lines). Categories overlap: 11 POMs, all ten module directories, 135 Java package-path files, 31 paths containing the branded class-name segment, and 83 of 92 tracked documentation files. There are 57 affected test classes. Review seven untracked documentation files separately, including this plan.

Additional surfaces: four embedded static-resource files, three main/test Liquibase-resource files, ignored `.idea/compiler.xml`, `.idea/encodings.xml`, `.idea/workspace.xml`, and 442 generated files under module `target/` directories. Regenerate build/IDE output; do not hand-edit it.

The untracked dashboard database contains real history (one instance, 647 snapshots, six failures at audit time); its backup contains one instance and 108 failures. Neither file is currently ignored. Add narrow ignore rules for dashboard SQLite runtime files and their sidecars/backups during stage 4, without excluding authored SQL resources. Check persisted registration URLs as well as UUIDs during migration. Historical documentation also mentions `blob_asset_content`, which the current initial migration does not upgrade.

Precise runtime regression suites include `EmbeddedDashboardIntegrationTest`, `ManagementDashboardContractTest`, `MultiInstanceDashboardIntegrationTest`, `ObservabilityStarterClasspathTest`, and `ObservabilityStarterContextTest`. Stage 3 must run these in addition to the renamed metrics tests.

Java/package/artifact and property renames are breaking consumer changes. Document dependency, import, configuration, endpoint, and persistence migration together. Hash-derived object-store keys do not contain branding and must remain unchanged.

On September 25, `./mvnw --batch-mode --no-transfer-progress verify` passed for the parent and all ten modules in 20.314 seconds. Log: `/tmp/dedup4j-baseline-20260925.log`. This is a baseline, not verification of a rename.

Current branch: `epic-006-structured-operational-logging`. Current origin: `https://github.com/heyEdem/blob-helper.git`.

Every implementation stage must finish with:

```sh
./mvnw --batch-mode --no-transfer-progress clean verify
git diff --check
git diff --stat
```

Run focused existing tests while editing, then the full checkpoint once. Stop on failure, fix within the same stage, and do not commit a failing stage. Record actual test results. Update only affected portions of architecture/implementation/pattern docs and append a dated changelog entry. Assess whether a new ADR is needed. Use explicit staging paths and a logical commit. After each commit run the project-required `git diff HEAD~1 --name-only` and check changed files/direct neighbors for documentation omissions.

## Stage 1 — Maven coordinates and module directories

**Files:** Root `pom.xml`; all ten child `pom.xml` files and module directories; core `ProviderDependencyBoundaryTest`; starter `GenericStarterDependencyTest`; documentation build commands and module maps.

**Interface:** Produce `com.edem:dedup4j:0.0.1-SNAPSHOT` and ten `dedup4j-*` children; Java APIs and runtime settings are changed in later stages.

- [ ] Rename each module directory with its suffix preserved, updating root `<modules>`, every child parent artifact, inter-module dependencies, and POM display metadata in the same change.
- [ ] Update tests that inspect POM names/module paths and documentation references to physical module locations. Do not move local database files merely because their containing module moves.
- [ ] Run boundary tests and the shared checkpoint; confirm all ten modules appear in the reactor summary.

```sh
./mvnw --batch-mode --no-transfer-progress -Dtest=ProviderDependencyBoundaryTest,GenericStarterDependencyTest verify
```

- [ ] Commit: `refactor: rename Maven modules and artifacts to dedup4j`.

## Stage 2 — Java packages, classes, and framework discovery

**Files:** All main/test Java trees under the renamed modules and root `src/`; `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`; test `META-INF/persistence.xml`; POM main-class declarations where present.

**Interface:** Produce package `com.edem.dedup4j`, facade `Dedup4j`, implementation `DefaultDedup4j`, exception `Dedup4jException`, and corresponding renamed auto-configuration/application classes.

- [ ] Move `com/edem/blobhelper` directories to `com/edem/dedup4j`; update imports, package declarations, fully qualified strings, class filenames, constructors, references, bean names, and matching tests.
- [ ] Update Spring import resources, entity scanning, classpath assertions, resource reflection, and JPA persistence-unit references consistently. Keep runtime property/SQL changes for their owning stages.
- [ ] Run automatic-discovery/context tests and the shared checkpoint. Inspect the root legacy application and its test separately: the parent POM does not compile those sources.

```sh
./mvnw --batch-mode --no-transfer-progress -Dtest=ProviderAutoConfigurationDiscoveryTest,Dedup4jContextStartTest,ObservabilityStarterClasspathTest,ObservabilityStarterContextTest verify
```

- [ ] Commit: `refactor: rename Java API and packages to dedup4j`.

## Stage 3 — Properties, routes, metrics, and dashboard branding

**Files:** Starter configuration/metrics/logging; management controller/properties/registration; embedded dashboard configuration/controllers/snapshot services/static resources; standalone application YAML and static UI; corresponding tests and usage documentation.

**Interface:** Produce `dedup4j.*` settings/meters, `/dedup4j/management` and `/dedup4j/dashboard` defaults, and packaged `static/dedup4j/dashboard` resources.

- [ ] Change property annotations, conditions, placeholders, sample YAML, error guidance, meter producers/readers, thread names, application display names, UI copy, and browser-storage keys together. Handle data-path defaults in stage 4.
- [ ] Move embedded static resources and update handler locations and classpath tests in the same change.
- [ ] Update route assertions and registration fixtures; retain custom-base-path behavior and explicit instance IDs. Verify management stays opt-in and both dashboards remain read-only.
- [ ] Run existing management, embedded dashboard, metrics, and multi-instance integration suites; the shared checkpoint must pass.
- [ ] Start the packaged standalone dashboard against a temporary SQLite path and unused loopback port; request `/`, its JS/CSS, and API routes discovered in `DashboardController`. Stop the process afterward. Use existing integration coverage for embedded default/custom routes and registration/polling.
- [ ] Commit: `refactor: rename runtime configuration and dashboards to dedup4j`.

## Stage 4 — Persistence identifiers and data locations

**Files:** JPA `AssetContent` mappings; starter persistence auto-configuration and `src/main/resources/db/blob-helper/`; schema tests; local storage defaults/tests; dashboard YAML/database settings; new `docs/dedup4j-migration.md`.

**Interface:** Produce new schema and filesystem naming without losing stored content or fleet history.

- [ ] Resolve the upgrade policy before changing schema names. For a fresh release, update schema resources and mappings consistently and preserve existing local files separately. For upgrades, use an explicit migration procedure against a copy; do not blindly edit applied Liquibase changesets or their recorded identity.
- [ ] Inventory affected table/index/constraint names and Liquibase changelog/lock tables; document old/new mappings in the migration guide. Change the resource folder, include paths, and validator diagnostics together.
- [ ] For supported upgrades, add coverage that begins with the original schema/data and verifies identical IDs, counts, hash identity, locations, successful readback, and no repeated migration on restart. Verify fresh initialization and `embedded`, `always`, and `never` policies with existing persistence tests.
- [ ] Change defaults to `dedup4j-storage` and `dedup4j-dashboard.sqlite`. Migrate only stopped, backed-up data copies; account for SQLite WAL/SHM state. Verify registration IDs and history survive. Retain original files until migrated copies are validated; never commit binary data.
- [ ] Run `AssetContentMappingTest`, renamed `Dedup4jPersistenceAutoConfigurationTest`, `LocalBlobStoragePropertiesTest`, `LocalStorageDeduplicationIntegrationTest`, fleet integration coverage, and the shared checkpoint.
- [ ] Record the persistence compatibility decision in `docs/decisions.md`; commit: `refactor: migrate persistence naming to dedup4j`.

## Stage 5 — Documentation, hidden files, and exhaustive residue audit

**Files:** `README.md`, all `docs/` content including ADRs/plans/epics, root resources, `.github/`, authored hidden configuration, tracked assets and filenames.

- [ ] Update remaining display text, dependency snippets, Java examples, HTTP examples, hyperlinks, diagrams, filenames, and file paths. Review ignored IDE metadata separately; regenerate derived metadata rather than modifying caches.
- [ ] Search case-insensitively for separated and joined variants, including the temporary mistaken spellings; inspect both contents and filenames:

```sh
rg -n -i --hidden -g '!.git/**' -g '!**/target/**' -g '!**/*.sqlite*' 'blob[ ._-]*helper|didup4g|dedup4g' .
rg --files --hidden -g '!.git/**' -g '!**/target/**' | rg -i 'blob[ ._-]*helper|didup4g|dedup4g'
```

- [ ] Classify every remaining match. Only deliberately approved migration-history references may remain in authored content; generated output must be rebuilt, local data handled through stage 4, and Git history left intact.
- [ ] Run the shared checkpoint and runtime smoke checks; review rendered UI branding and documentation links.
- [ ] Commit: `docs: finish dedup4j rebrand and migration guidance`.

## Stage 6 — Repository and checkout handoff

- [ ] Edem renames GitHub repository to `heyEdem/dedup4j` and checkout directory to `/Users/Edem/Documents/IdeaProjects/dedup4j` after closing active development processes.
- [ ] From the new directory, verify repository identity, update origin to `https://github.com/heyEdem/dedup4j.git`, refresh IDE Maven import, and update actual links in documentation. Do not assume a planned remote name is already live.
- [ ] Run a final clean verification from the new root and repeat standalone startup/HTTP checks. Confirm repository links and CI work under the new name.
- [ ] Commit any remaining tracked link changes. If execution is recorded as a milestone task, follow project policy: push the milestone branch and open a PR to `main`, using the global `pr-writer` skill and actual diff/test evidence before drafting remote PR metadata.

## Completion criteria

- [ ] Ten renamed modules build and all required tests pass from the final checkout location.
- [ ] Consumer startup, upload/deduplication/readback/release, metrics, management, and both dashboards retain behavior.
- [ ] No unclassified legacy-brand content or filenames remain in the working tree.
- [ ] Existing files and database history are preserved according to the chosen migration policy.
- [ ] Every implementation stage has its own green result and logical commit.

## Planning status

This is a planning deliverable. No application rename, data migration, repository rename, or implementation commit has been performed.

## Module-by-module execution — September 25

Per Edem’s execution instruction, implement and verify one module per commit, including necessary consumer references. Keep persistence changes gated on the compatibility decision. Existing repository and checkout renames remain Edem-owned.

- [x] Core module and consumer imports — clean reactor verify passed (22.993 s); dependency boundary and all downstream tests passed.
- [x] JPA module — clean reactor verify: 189 tests, zero failures/errors.
- [x] Local storage module — clean reactor verify: 189 tests, zero failures/errors.
- [ ] S3 module
- [ ] Azure module
- [ ] Spring Boot starter
- [ ] Management module
- [ ] Embedded dashboard module
- [ ] Observability aggregate
- [ ] Standalone dashboard
- [ ] Parent, documentation, persistence, and residue audit
