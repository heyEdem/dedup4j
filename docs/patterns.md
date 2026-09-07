# Patterns

## Naming Conventions

- Files: Java source files use PascalCase class names matching filenames.
- Classes/types: PascalCase, e.g. `BlobHelperApplication`, `CoreModuleSmokeTest`.
- Functions/methods: lowerCamelCase, e.g. `main`, `contextLoads`, `coreModuleTestsRunInExpectedPackage`.
- Variables: lowerCamelCase where present.
- Docs: ADR files use `ADR-###-kebab-case-title.md`; implementation plans use `PLAN-###-kebab-case-title.md`; epic tasks use `task-###-kebab-case-title.md`.

## Folder Conventions

- Maven modules live at repository root, e.g. `blob-helper-core`.
- Java production code follows `src/main/java`.
- Java tests follow `src/test/java`.
- Project planning docs live under `docs/`.
- ADRs live under `docs/adrs/`.
- Phase plans live under `docs/implementation-plans/`.
- Epic task files live under `docs/epics/<epic>/tasks/`.

## Recurring Code Patterns

- Error handling: not enough implemented code to determine project-specific error handling.
- Async: not present in current implementation.
- Provider dependency injection: starter-owned auto-configurations activate on `blob-helper.storage.provider`, bind settings independently, and back off their entire default graph for an application `BlobStorage`. Individual provider properties and clients also use missing-bean conditions. Final validation counts storage beans by type rather than bean name.
- S3 lifecycle: Spring owns client-bean cleanup; the auto-configured storage adapter disables inferred destruction to avoid closing the same client twice.
- Validation: constructors reject null or blank required text and invalid negative sizes before state crosses a module boundary.
- Testing: current tests are JUnit Jupiter tests with package-private test classes.
- Dependency boundaries: reusable modules pair classpath-level package scanning with Maven Enforcer rules so both loaded classes and direct/transitive artifact coordinates are guarded.
- JPA entities: use field access, a protected no-argument constructor, explicit snake_case column names, constructor validation for required metadata, portable lifecycle callbacks for timestamps, and standard `@Version` optimistic locking.
- JPA repositories: wrap a caller-owned `EntityManager`, return `Optional` for lookup methods, and apply `LockModeType.PESSIMISTIC_WRITE` for mutation workflows that must hold a row lock through the caller's transaction.
- JPA create-or-retain mutation: flush a new identity insert inside the operation and classify SQL state `23505` as `DuplicateContentIdentityException`. The transaction owner rolls back before reloading and retaining the winner; JPA production code never restarts transactions or clears the persistence context.
- Metadata schema naming: Blob Helper uses `blob_helper_asset_content`, with `uk_blob_helper_asset_content_identity` and `idx_blob_helper_asset_content_*` constraint/index names.
- Service facade boundaries: application-facing starter services return only core models, delegate transaction-scoped metadata mutations to JPA services, and leave logical asset ownership with consuming applications.
- Spring transaction ownership: expose one transactional decorator bean and construct its raw delegate internally. Use `REQUIRES_NEW` for public operations and recover duplicate identity races only after rollback, without replaying upload input or storage IO.
- Schema lifecycle: package versioned Liquibase resources under `db/blob-helper`, use dedicated migration tracking tables, guard mutation with `initialize-schema`, and validate mapped columns before JPA initialization.
- Local publication: finish streaming into a same-directory temporary file before replacing the destination; prefer atomic moves and preserve existing content on stream failure. Keep concurrency handling in the storage adapter rather than adding global locks to the service.
- Operational logging: use SLF4J key/value-style messages at service decision boundaries, include content ID/provider/object key/decision context, expose only a short explicit hash prefix, and attach the original exception to failed physical-delete events.
- Planned dashboard boundaries: instance-side management remains optional and read-only; dashboard-side registration, polling, persistence, and UI remain in the separate dashboard application. Dashboard timestamps use UTC, and successful operations are aggregated rather than persisted as raw events.

## Testing Conventions

- Test file location: `src/test/java` within each module.
- Test naming: descriptive lowerCamelCase methods, e.g. `coreModuleTestsRunInExpectedPackage`.
- Test helpers: none observed.
- Run all current tests with `./mvnw test`.
- Run core tests with `./mvnw -pl blob-helper-core test`.
- Run JPA mapping tests with `./mvnw -pl blob-helper-jpa test`; they use a real Hibernate persistence unit backed by in-memory H2.
- Name dependency boundary tests `*BoundaryTest` so they can be run together with `./mvnw test -Dtest='*BoundaryTest'`.
- Name dashboard integration tests descriptively around registration, polling isolation, counter-reset handling, and seven-day failure retention; keep them credential-free with in-process HTTP endpoints and temporary SQLite databases.

## Pull Request Conventions

- Use the global `$pr-writer` skill for every pull request title and body, including reviews and updates to existing pull requests.
- Derive claims from the actual branch/base, commit range, diff, test results, and issue context.
- Use a conventional-commit title and the reviewer-oriented sections defined by the skill; verify the remote pull request after creation or editing.

## Anti-Patterns Observed

- Root `src/` still contains a Spring Boot shell class while the root project is `pom` packaging. This is likely transitional during conversion to a multi-module library.
