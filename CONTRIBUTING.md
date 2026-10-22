# Contributing

Thanks for contributing to dedup4j. The project values a small, honest API, correct reference counting, and tests that prove behaviour. Please read this before opening a pull request.

User documentation lives at [dedup4j-docs.akato.dev](https://dedup4j-docs.akato.dev); its source is the separate [`heyEdem/dedup4j-docs`](https://github.com/heyEdem/dedup4j-docs) repository.

## Before you start

- Check the [open issues](https://github.com/heyEdem/dedup4j/issues) and the current milestone. Comment on an issue before starting work on it.
- For anything larger than a bug fix (new module, new public API, schema change), open an issue first and agree the design. A pull request is the wrong place to discover that a design is unwanted.
- Security problems: do not open a public issue. Report them privately through [**Security → Report a vulnerability**](https://github.com/heyEdem/dedup4j/security/advisories/new).

## Never skip tests

This is the most important rule in the project.

When a test fails, fix the code or fix the test, and find the root cause. Never `@Disabled` a test, never pass `-DskipTests` to get a change through, and never weaken an assertion to make it pass. This applies to every contributor, human or AI.

## Getting started

Requirements:

- **Java 21**
- **Docker**, for the PostgreSQL Testcontainers tests

```bash
git clone https://github.com/heyEdem/dedup4j
cd dedup4j
./mvnw clean verify
```

`verify` is exactly what CI runs. A green build reports every test passed with none skipped.

If Docker runs somewhere other than the default socket (OrbStack, Colima, Rancher Desktop), point Testcontainers at it:

```bash
DOCKER_HOST=unix:///path/to/docker.sock TESTCONTAINERS_RYUK_DISABLED=true ./mvnw clean verify
```

Tests never need cloud credentials. Anything that requires a real S3 or Azure account is not a test for this repository.

## Project layout

| Module | Contains |
|---|---|
| `dedup4j-core` | Hashing, models and the `BlobStorage` SPI. **No runtime dependencies** |
| `dedup4j-jpa` | `AssetContent` metadata, reference counting, upload history |
| `dedup4j-storage-local`, `-s3`, `-azure` | Storage adapters. Each depends only on `dedup4j-core` and its SDK |
| `dedup4j-spring-boot-starter` | Auto-configuration, the deduplication service and the `Dedup4j` facade |
| `dedup4j-spring-boot-management` | Opt-in management API under `/dedup4j/management/v1` |
| `dedup4j-spring-boot-dashboard` | Embedded read-only dashboard |
| `dedup4j-spring-boot-observability` | Aggregate of management and the embedded dashboard |
| `dedup4j-dashboard` | Standalone fleet dashboard (not published to Maven Central) |

Design notes: `docs/architecture.md`, `docs/implementation.md` and the ADRs in `docs/adrs/`.

## Rules the code must keep

- **Every bug fix starts with a failing regression test.** Write the test, watch it fail for the right reason, then fix the code.
- **`dedup4j-core` stays dependency-free**, and each provider SDK is declared only by its own adapter module. `ProviderDependencyBoundaryTest` enforces the second rule.
- **An unselected storage provider constructs no client and resolves no credentials.** Having the S3 SDK on the classpath must never mean contacting AWS.
- **Reference counts are changed only under the row lock** (`AssetContentRepository.findByIdForUpdate`). Any new path that retains or releases content needs a test for the concurrent case.
- **Upload history is append-only.** Never update or delete history rows; record corrections as new events.
- **Public API changes need a reason.** Adding a field to `BlobReference` or a new `dedup4j.*` property is a commitment to every consumer.

## Adding a storage provider

Implement `BlobStorage` from `dedup4j-core` in its own module. The contract (idempotent `delete`, `get` returns an open stream, cheap `exists`, `put` tolerates existing content) is documented in [Storage providers](https://dedup4j-docs.akato.dev/docs/guides/providers). Credentials come from the SDK's default chain, never from a `dedup4j.*` property.

## Commits

Use [Conventional Commits](https://www.conventionalcommits.org/):

```text
type(scope): what changed, in the imperative
```

Types used here: `feat`, `fix`, `docs`, `test`, `refactor`, `build`, `chore`. Scope is the area, for example `history`, `jpa`, `management`, `readme`. Keep commits logical: one concern per commit, each one building and passing tests.

## Branches and pull requests

1. Branch from an up-to-date `main`: `feat/<issue>-short-name`, `fix/<issue>-short-name`, `docs/…`, `chore/…`.
2. Run `./mvnw clean verify` locally before pushing.
3. Open the pull request against `main` and describe:
   - what changed and why,
   - which tests prove it (by name),
   - how a reviewer can reproduce it.
4. Link the issue with a closing keyword in the description, for example `Closes #65`, so it closes on merge.
5. CI must pass: **Maven Verify** and **dependency-review**.
6. Add a dated entry to `docs/changelog.md` for user-visible changes, and update the docs site in `dedup4j-docs` when behaviour or configuration changes.

## Releases

Releases to Maven Central are cut by the maintainer from a tagged commit on `main`. Contributors do not need to change versions.

## License

dedup4j is licensed under the [Apache License 2.0](LICENSE). By submitting a contribution, you agree that it is licensed under the same terms.
