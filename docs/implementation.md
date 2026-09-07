# Implementation

## Entry Points

- `pom.xml`: parent Maven reactor. Declares the core, persistence, starter, storage, management, and dashboard modules, manages JUnit/Spring Boot dependency BOMs, and configures Surefire to ignore a named test pattern in modules that do not contain that test.
- `blob-helper-core/pom.xml`: core module build file. Depends on JUnit Jupiter for tests.
- `blob-helper-jpa/pom.xml`: persistence module build file. Depends on `blob-helper-core`, exposes Jakarta Persistence, and uses Hibernate/H2 in test scope.
- `blob-helper-jpa/src/main/java/com/edem/blobhelper/jpa/AssetContent.java`: JPA entity for unique physical content identity, object location, metadata, reference count, timestamps, and optimistic locking.
- `blob-helper-jpa/src/main/java/com/edem/blobhelper/jpa/AssetContentRepository.java`: EntityManager-backed repository for complete identity lookup, unlocked metadata lookup by content id, read-all metadata queries, and pessimistic write-locked lookup by content id; transaction lifecycle remains with the caller.
- `blob-helper-jpa/src/main/java/com/edem/blobhelper/jpa/AssetContentMutationService.java`: create-or-retain service that flushes new inserts and reports SQL-state `23505` through `DuplicateContentIdentityException`; the transaction owner rolls back before recovering. Its two-argument constructor reuses an application-provided repository.
- `blob-helper-jpa/src/main/java/com/edem/blobhelper/jpa/DuplicateContentIdentityException.java`: carries the complete algorithm/hash/size identity and original persistence failure across the transaction boundary.
- `blob-helper-jpa/src/main/java/com/edem/blobhelper/jpa/ReferenceCountService.java`: lock-aware service that retains and releases one reference at a time, rejects missing content, prevents reference-count underflow, and delegates physical deletion to `BlobStorage` only when release reaches zero while leaving transaction ownership with the caller.
- `blob-helper-jpa/src/test/java/com/edem/blobhelper/jpa/AssetContentMappingTest.java`: boots Hibernate against H2 and verifies persistence state, validation, table naming, identity uniqueness, and indexes.
- `blob-helper-jpa/src/test/java/com/edem/blobhelper/jpa/AssetContentRepositoryTest.java`: verifies complete identity lookups, pessimistic write lock acquisition, missing-row behavior, constructor validation, and duplicate identity rejection.
- `blob-helper-jpa/src/test/java/com/edem/blobhelper/jpa/AssetContentMutationServiceTest.java`: verifies new-row creation, ordinary duplicate retention, and a coordinated concurrent insert race that reloads the winner and increments its count once.
- `blob-helper-jpa/src/test/java/com/edem/blobhelper/jpa/ConcurrentUploadIntegrationTest.java`: verifies two parallel create-or-retain workers converge on one identity row and the final reference count equals the worker count.
- `blob-helper-spring-boot-starter/pom.xml`: standard starter build file. It depends at compile scope on the local, S3, and Azure adapter modules so one consumer dependency supplies every supported provider; provider SDK coordinates remain owned by those adapter POMs. It also carries Spring Boot auto-configuration, Micrometer Core, and configuration-processor dependencies.
- `blob-helper-spring-boot-starter/src/main/java/com/edem/blobhelper/autoconfigure/BlobHelperProperties.java`: binds storage, deduplication, and cleanup settings under the `blob-helper` prefix, including upload-size parsing and reconciliation defaults.
- `blob-helper-spring-boot-starter/src/main/java/com/edem/blobhelper/autoconfigure/BlobHelperAutoConfiguration.java`: enables `BlobHelperProperties` and installs a final startup validator requiring a supported explicit provider and exactly one `BlobStorage`, independent of bean names or `@Primary`.
- `blob-helper-spring-boot-starter/src/main/java/com/edem/blobhelper/autoconfigure/storage/`: local, S3, and Azure auto-configurations translate nested starter settings into adapter properties and conditionally create the selected provider's client/storage beans. Application storage suppresses the entire default graph; application provider clients take precedence over default clients.
- `blob-helper-spring-boot-starter/src/main/java/com/edem/blobhelper/autoconfigure/persistence/`: additive entity discovery, guarded `blobHelperLiquibase` initialization, schema validation before entity-manager-factory initialization, and consumer migration coexistence. `SchemaInitialization` binds `EMBEDDED`, `ALWAYS`, and `NEVER`.
- `blob-helper-spring-boot-starter/src/main/java/com/edem/blobhelper/autoconfigure/service/BlobHelperServiceAutoConfiguration.java`: independently replaceable repository, mutation, reference-count, hasher, key-strategy, metrics, and public service defaults. Activates after persistence/provider configurations; the raw delegate is constructed inside the transactional service factory and is not a bean. Reuses an application EntityManager or creates an internal shared proxy from the managed factory, including when Spring Data repositories are disabled.
- `blob-helper-spring-boot-starter/src/main/java/com/edem/blobhelper/service/SpringTransactionalBlobDeduplicationService.java`: wraps every public operation in `REQUIRES_NEW`. A duplicate identity failure rolls back before a second transaction reloads and retains the winner; the original stream and storage write are not replayed.
- `blob-helper-spring-boot-starter/src/main/java/com/edem/blobhelper/service/BlobReferences.java`: package-private mapping shared by the raw service and transactional recovery.
- `blob-helper-spring-boot-starter/src/main/resources/db/blob-helper/`: versioned metadata changelog, matching prefixed entity/constraint/index names. The initial changelog creates the new schema; it does not migrate the legacy `blob_asset_content` data.
- `blob-helper-spring-boot-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`: Spring Boot 3 registration for the three provider configurations and the final `BlobHelperAutoConfiguration`; explicit ordering places provider definitions before validation.
- `blob-helper-spring-boot-starter/src/main/java/com/edem/blobhelper/service/BlobDeduplicationService.java`: provider-neutral application-facing contract for store, stable location lookup, retain, release, and get operations.
- `blob-helper-spring-boot-starter/src/main/java/com/edem/blobhelper/service/DefaultBlobDeduplicationService.java`: default advanced service that delegates reference mutation and content retrieval to JPA/storage collaborators and orchestrates uploads by buffering bytes, enforcing declared stream size, timing hashing with `ContentHasher`, looking up complete content identity, recording upload/duplicate/byte-savings metrics, retaining duplicates without physical writes, generating deterministic keys for new content, timing physical writes through `BlobStorage`, returning complete stable locations, and creating metadata through `AssetContentMutationService`; provider delete failures are counted before being rethrown and logged with reconciliation context.
- `blob-helper-spring-boot-starter/src/main/java/com/edem/blobhelper/facade/BlobHelper.java`: Spring-facing convenience upload contract for `MultipartFile`, `Path`, byte-array, described-stream, and ordered batch sources.
- `blob-helper-spring-boot-starter/src/main/java/com/edem/blobhelper/facade/DefaultBlobHelper.java`: adapts common sources into one `StoreBlobCommand` delegation path, applies the configured maximum upload size, translates source `IOException`s into validation failures, and continues sequential batches after per-item runtime failures.
- `blob-helper-spring-boot-starter/src/main/java/com/edem/blobhelper/facade/BatchStoreOutcome.java`: sealed provider-neutral success/failure outcome contract retaining the source index and filename.
- `blob-helper-spring-boot-starter/src/main/java/com/edem/blobhelper/facade/BlobStoreSuccess.java`: validated successful batch outcome containing the returned `BlobReference`.
- `blob-helper-spring-boot-starter/src/main/java/com/edem/blobhelper/facade/BlobStoreFailure.java`: validated failed batch outcome containing the original runtime failure.
- `blob-helper-spring-boot-starter/src/main/java/com/edem/blobhelper/facade/BatchStoreResult.java`: immutable ordered batch aggregate with typed success/failure views and an all-succeeded check.
- `blob-helper-spring-boot-starter/src/main/java/com/edem/blobhelper/observability/BlobHelperMetrics.java`: optional Micrometer facade with counters for uploads, duplicate outcomes, accepted/avoided bytes, delete failures, and repairs, plus timers for hashing and physical storage writes; a null registry provides a no-op path.
- `blob-helper-spring-boot-starter/src/main/java/com/edem/blobhelper/reconcile/LogicalReferenceCountSource.java`: functional application callback contract that supplies logical reference counts keyed by Blob Helper `AssetContent` IDs without assuming the consuming application's schema.
- `blob-helper-spring-boot-starter/src/main/java/com/edem/blobhelper/reconcile/ReconciliationMismatch.java`: immutable validated value for one expected-versus-actual reference-count difference.
- `blob-helper-spring-boot-starter/src/main/java/com/edem/blobhelper/reconcile/ReconciliationReport.java`: immutable validated aggregate of checked content count and mismatches; report creation is separate from repair commands.
- `blob-helper-spring-boot-starter/src/main/java/com/edem/blobhelper/reconcile/ReconciliationService.java`: reconciliation service that compares every stored `AssetContent.ref_count` with application-provided counts, treats omitted content IDs as zero expected references, and exposes a separately invoked repair operation that is disabled by default, adjusts counts only through `ReferenceCountService`, and records each applied repair.
- `blob-helper-spring-boot-starter/src/test/java/com/edem/blobhelper/autoconfigure/BlobHelperPropertiesTest.java`: verifies relaxed Spring Boot binding for configured values and the disabled-by-default reconciliation setting.
- `blob-helper-spring-boot-starter/src/test/java/com/edem/blobhelper/autoconfigure/BlobHelperAutoConfigurationTest.java`: verifies explicit selection, one active provider with all adapters present, custom storage independent of bean names, and actionable missing/unsupported/ambiguous wiring failures.
- `blob-helper-spring-boot-starter/src/test/java/com/edem/blobhelper/autoconfigure/storage/`: context coverage for provider activation, property translation, client reuse/default construction, full custom-storage back-off, and S3 client lifecycle without external cloud calls.
- `blob-helper-spring-boot-starter/src/test/java/com/edem/blobhelper/autoconfigure/ProviderAutoConfigurationDiscoveryTest.java`: verifies provider isolation with every adapter on the classpath and a minimal consumer using Spring Boot's automatic imports discovery.
- `blob-helper-spring-boot-starter/src/test/java/com/edem/blobhelper/autoconfigure/BlobHelperContextStartTest.java`: automatic-discovery consumer startup with default and validating JPA settings, public operation round-trip, and a forced duplicate-insert race asserting one SQL-state `23505`, one metadata row/object, and two references.
- `blob-helper-spring-boot-starter/src/test/java/com/edem/blobhelper/autoconfigure/persistence/BlobHelperPersistenceAutoConfigurationTest.java`: guarded schema modes, missing-column guidance, Liquibase coexistence, additive entity packages, and migration/Hibernate parity.
- `blob-helper-spring-boot-starter/src/test/java/com/edem/blobhelper/autoconfigure/service/BlobHelperServiceAutoConfigurationTest.java`: complete default graph including the convenience façade, absence-of-persistence back-off, façade replacement, separate contexts for individual overrides, and optional metrics registry wiring.
- `blob-helper-spring-boot-starter/src/test/java/com/edem/blobhelper/service/SpringTransactionalBlobDeduplicationServiceTest.java`: all public operation transaction boundaries and explicit rollback-before-retry event ordering, with one-shot input and missing-winner behavior.
- `blob-helper-spring-boot-starter/src/test/java/com/edem/blobhelper/service/BlobDeduplicationServiceContractTest.java`: verifies the service method signatures, provider-neutral return types, metadata-only stable location lookup, and missing-content exception behavior.
- `blob-helper-spring-boot-starter/src/test/java/com/edem/blobhelper/service/BlobDeduplicationServiceTest.java`: verifies new and duplicate upload orchestration against Hibernate/H2 with a recording storage fake: one physical write, one metadata row, complete stable locations, reference retention, duplicate decision reporting, and declared-size mismatch rejection.
- `blob-helper-spring-boot-starter/src/test/java/com/edem/blobhelper/facade/DefaultBlobHelperTest.java`: verifies exact source metadata adaptation, one-call identity-preserving delegation, source-opening failures, and configured upload-size rejection.
- `blob-helper-spring-boot-starter/src/test/java/com/edem/blobhelper/facade/DefaultBlobHelperBatchTest.java`: verifies ordered mixed outcomes, duplicate success, null-item continuation, and null-array validation.
- `blob-helper-spring-boot-starter/src/test/java/com/edem/blobhelper/facade/BlobHelperMvcUsageTest.java`: proves consuming controllers can bind `MultipartFile[]` directly and map façade results without a library controller.
- `blob-helper-spring-boot-starter/src/test/java/com/edem/blobhelper/observability/BlobHelperLoggingTest.java`: captures the real SLF4J/Logback output and verifies new/duplicate upload context, explicit short hash prefixes, and failed-delete reconciliation context.
- `blob-helper-spring-boot-starter/src/test/java/com/edem/blobhelper/observability/BlobHelperMetricsTest.java`: verifies upload/duplicate counters, accepted and avoided byte totals, hashing/storage timers, delete-failure and repair counters, and the no-op behavior without a registry.
- `blob-helper-spring-boot-starter/src/test/java/com/edem/blobhelper/autoconfigure/GenericStarterDependencyTest.java`: proves the starter test classpath contains local, S3, and Azure adapter classes while management, embedded-dashboard, and standalone-dashboard classes remain absent.
- `blob-helper-spring-boot-starter/src/test/java/com/edem/blobhelper/service/LocalStorageDeduplicationIntegrationTest.java`: verifies the complete service path with a real temporary-directory local provider: readback, one physical file for duplicate uploads, and final-reference deletion.
- `blob-helper-spring-boot-starter/src/test/java/com/edem/blobhelper/reconcile/ReconciliationContractsTest.java`: verifies callback usage, expected/actual mismatch values, immutable report collections, and validation of invalid counts and IDs.
- `blob-helper-spring-boot-starter/src/test/java/com/edem/blobhelper/reconcile/ReconciliationServiceTest.java`: Hibernate/H2 integration coverage for mismatch reporting, omitted IDs, checked-row totals, disabled no-mutation behavior, and enabled retain/release repair including final-reference storage deletion.
- `blob-helper-core/src/main/java/com/edem/blobhelper/core/package-info.java`: package marker for provider-neutral core APIs.
- `blob-helper-core/src/main/java/com/edem/blobhelper/core/hash/ContentHasher.java`: stream-based content hashing contract.
- `blob-helper-core/src/main/java/com/edem/blobhelper/core/hash/ContentHash.java`: content identity value carrying algorithm, hash, and byte size.
- `blob-helper-core/src/main/java/com/edem/blobhelper/core/hash/Sha256ContentHasher.java`: streaming SHA-256 implementation that returns lowercase hex.
- `blob-helper-core/src/main/java/com/edem/blobhelper/core/key/ObjectKeyStrategy.java`: contract for generating storage object keys from content identity.
- `blob-helper-core/src/main/java/com/edem/blobhelper/core/key/HashObjectKeyStrategy.java`: deterministic key strategy using `{prefix}/{algorithm}/{first_two_hash_chars}/{content_hash}`.
- `blob-helper-core/src/main/java/com/edem/blobhelper/core/storage/BlobStorage.java`: provider-neutral storage SPI for put, get, idempotent delete, and existence checks.
- `blob-helper-core/src/main/java/com/edem/blobhelper/core/storage/PutBlobRequest.java`: validated streaming upload request with immutable metadata.
- `blob-helper-core/src/main/java/com/edem/blobhelper/core/storage/StoredBlob.java`: provider-neutral persisted object metadata, including provider and bucket/container location.
- `blob-helper-core/src/main/java/com/edem/blobhelper/core/storage/BlobResource.java`: validated read resource that owns and closes its content stream.
- `blob-helper-core/src/main/java/com/edem/blobhelper/core/model/StoreBlobCommand.java`: application-facing streaming store command without a caller-controlled object key.
- `blob-helper-core/src/main/java/com/edem/blobhelper/core/model/BlobLocation.java`: validated provider/bucket-or-container/object-key value for stable physical storage identity.
- `blob-helper-core/src/main/java/com/edem/blobhelper/core/model/BlobReference.java`: stored-content reference carrying the asset-content ID, content identity, complete stable location, and duplicate decision.
- `blob-helper-core/src/main/java/com/edem/blobhelper/core/exception`: unchecked, provider-neutral exception hierarchy for validation, hashing, storage, missing content, and reference-count underflow.
- `blob-helper-core/src/test/java/com/edem/blobhelper/core/CoreModuleBoundaryTest.java`: scans the effective test classpath and fails when Spring, JPA, AWS SDK, or Azure SDK classes enter core.
- `blob-helper-core/src/test/java/com/edem/blobhelper/core/ProviderDependencyBoundaryTest.java`: parses the root reactor and child POMs to verify AWS SDK coordinates belong only to `blob-helper-storage-s3` and Azure SDK coordinates only to `blob-helper-storage-azure`.
- `blob-helper-core/src/test/java/com/edem/blobhelper/core/CoreModuleSmokeTest.java`: verifies the core test package is wired.
- `blob-helper-storage-local/pom.xml`: local storage adapter module build file. Depends only on `blob-helper-core` and JUnit Jupiter; no cloud SDKs.
- `blob-helper-storage-local/src/main/java/com/edem/blobhelper/storage/local/LocalBlobStorageProperties.java`: local provider configuration with a configurable `rootDirectory` (defaults to `blob-helper-storage`) that rejects null assignment.
- `blob-helper-storage-local/src/test/java/com/edem/blobhelper/storage/local/LocalBlobStoragePropertiesTest.java`: verifies the default root directory, custom root binding, and null rejection.
- `blob-helper-storage-local/src/main/java/com/edem/blobhelper/storage/local/LocalBlobStorage.java`: filesystem `BlobStorage` adapter that resolves object keys under the configured absolute root, streams uploads with parent-directory creation and overwrite semantics, returns owner-managed `BlobResource` streams, throws core `ContentNotFoundException` for missing reads, deletes idempotently via `Files.deleteIfExists`, and reports existence from the filesystem. Key resolution normalizes the resolved path and rejects keys that escape or equal the root with core `BlobValidationException` before any file IO.
- `blob-helper-storage-local/src/test/java/com/edem/blobhelper/storage/local/LocalBlobStorageIntegrationTest.java`: JUnit temporary-directory coverage for the put/get/delete round trip with filesystem assertions, idempotent missing-object delete, pre-write existence checks, overwrite behavior, blank-key rejection, and traversal protection for `../`, absolute-path, and self-resolving keys plus valid nested-key access.
- `blob-helper-storage-s3/pom.xml`: S3 provider module build file with `blob-helper-core`, JUnit, and an AWS SDK for Java 2.x BOM plus `software.amazon.awssdk:s3` dependency scoped to this module.
- `blob-helper-storage-s3/src/main/java/com/edem/blobhelper/storage/s3/S3BlobStorageProperties.java`: provider-specific settings for bucket, region, optional endpoint override, and path-style access, with path-style access disabled by default.
- `blob-helper-storage-s3/src/main/java/com/edem/blobhelper/storage/s3/S3BlobStorage.java`: S3 `BlobStorage` adapter that uploads through `RequestBody.fromInputStream`, exposes `ResponseInputStream` through `BlobResource`, uses `headObject` for existence checks, deletes idempotently, and maps provider failures to core validation, not-found, and storage exceptions. It supports properties-based client construction and injected clients for tests.
- `blob-helper-storage-s3/src/test/java/com/edem/blobhelper/storage/s3/S3BlobStoragePropertiesTest.java`: verifies S3 connection settings and the default path-style access setting without credentials or network calls.
- `blob-helper-storage-s3/src/test/java/com/edem/blobhelper/storage/s3/S3BlobStorageContractTest.java`: verifies the S3 adapter round trip, request metadata, missing-object behavior, idempotent deletion, and provider exception mapping with an in-process SDK proxy.
- `blob-helper-storage-azure/pom.xml`: Azure provider module build file with `blob-helper-core`, JUnit, and a module-local Azure SDK BOM plus `com.azure:azure-storage-blob` dependency.
- `blob-helper-storage-azure/src/main/java/com/edem/blobhelper/storage/azure/AzureBlobStorageProperties.java`: provider configuration bean for the Azure container, connection string, optional endpoint, and account name; it contains no Azure SDK types.
- `blob-helper-storage-azure/src/main/java/com/edem/blobhelper/storage/azure/AzureBlobStorage.java`: Azure `BlobStorage` adapter with properties-based or injected-client construction, streaming uploads with content headers and metadata, property-backed reads, idempotent deletion, existence checks, and Azure-to-core exception mapping.
- `blob-helper-storage-azure/src/test/java/com/edem/blobhelper/storage/azure/AzureBlobStoragePropertiesTest.java`: verifies all Azure connection settings can be assigned and read without credentials or network calls.
- `blob-helper-storage-azure/src/test/java/com/edem/blobhelper/storage/azure/AzureBlobStorageContractTest.java`: verifies the Azure adapter round trip, request headers and metadata, missing-object behavior, idempotent deletion, and provider exception mapping against an in-process HTTP fake.
- `blob-helper-spring-boot-management/pom.xml`: optional instance management module build file; it depends on the starter, Spring Web, and Micrometer without cloud-provider SDKs.
- `blob-helper-spring-boot-management/src/main/java/com/edem/blobhelper/management/BlobHelperManagementProperties.java`: binds disabled-by-default management enablement, base path, instance ID, and instance name.
- `blob-helper-spring-boot-management/src/main/java/com/edem/blobhelper/management/BlobHelperManagementSnapshot.java`: provider-neutral info, health, metrics, and failure response records plus the failure-source extension point.
- `blob-helper-spring-boot-management/src/main/java/com/edem/blobhelper/management/BlobHelperManagementController.java`: exposes GET-only `/v1/info`, `/health`, `/metrics`, and `/failures` endpoints under the configurable management base path.
- `blob-helper-spring-boot-management/src/main/java/com/edem/blobhelper/management/BlobHelperManagementAutoConfiguration.java`: conditionally registers the management controller only when `blob-helper.management.enabled=true`.
- `blob-helper-spring-boot-management/src/main/java/com/edem/blobhelper/management/DashboardRegistrationProperties.java`: binds opt-in dashboard URL, instance identity, advertised management URL, and optional explicit stable ID.
- `blob-helper-spring-boot-management/src/main/java/com/edem/blobhelper/management/InstanceRegistrationClient.java`: asynchronously self-registers after application readiness, derives a stable name-based UUID when needed, and isolates dashboard outages from application startup.
- `blob-helper-spring-boot-dashboard/pom.xml`: optional embedded dashboard starter with Spring Boot auto-configuration, MVC APIs, and packaged static resources.
- `blob-helper-spring-boot-dashboard/src/main/java/com/edem/blobhelper/dashboard/autoconfigure/BlobHelperDashboardProperties.java`: binds enabled state, normalized base path, and failure lookback under `blob-helper.dashboard`.
- `blob-helper-spring-boot-dashboard/src/main/java/com/edem/blobhelper/dashboard/autoconfigure/BlobHelperDashboardAutoConfiguration.java`: conditionally registers the embedded dashboard in servlet web applications when enabled.
- `blob-helper-spring-boot-dashboard/src/main/java/com/edem/blobhelper/dashboard/api/EmbeddedDashboardSnapshotService.java`: creates zero-safe current-process metric snapshots from optional Micrometer/JPA collaborators.
- `blob-helper-spring-boot-dashboard/src/main/java/com/edem/blobhelper/dashboard/api/EmbeddedDashboardController.java`: exposes GET-only overview, status, empty history, and failure routes and maps the packaged UI for custom base paths.
- `blob-helper-spring-boot-dashboard/src/main/java/com/edem/blobhelper/dashboard/api/EmbeddedDashboardView.java`: dashboard-shaped immutable JSON view records matching the standalone console’s field meanings.
- `blob-helper-spring-boot-dashboard/src/main/resources/static/blob-helper/dashboard`: embedded light/dark responsive UI with relative API requests.
- `blob-helper-dashboard/pom.xml`: standalone executable dashboard with Spring Web, Spring JDBC, SQLite JDBC, and Spring Boot repackaging.
- `blob-helper-dashboard/src/main/java/com/edem/blobhelper/dashboard/BlobHelperDashboardApplication.java`: standalone dashboard entry point with loopback/9090 defaults.
- `blob-helper-dashboard/src/main/java/com/edem/blobhelper/dashboard/registration/InstanceRegistration.java`: validated provider-neutral registration record.
- `blob-helper-dashboard/src/main/java/com/edem/blobhelper/dashboard/registration/InstanceRegistrationController.java`: local registration endpoint backed by stable-ID SQLite upsert and readback.
- `blob-helper-dashboard/src/main/java/com/edem/blobhelper/dashboard/persistence/DashboardDatabase.java`: initializes SQLite tables and indexes for instances, metric snapshots, and failure events.
- `blob-helper-dashboard/src/main/java/com/edem/blobhelper/dashboard/persistence/InstanceRepository.java`: parameterized registration/status repository with last-seen and failure state.
- `blob-helper-dashboard/src/main/java/com/edem/blobhelper/dashboard/persistence/MetricSnapshotRepository.java`: stores ordered per-instance interval metric snapshots.
- `blob-helper-dashboard/src/main/java/com/edem/blobhelper/dashboard/persistence/FailureEventRepository.java`: stores failure details and deletes only events older than the configured retention window.
- `blob-helper-dashboard/src/main/java/com/edem/blobhelper/dashboard/polling/MetricDeltaCalculator.java`: converts cumulative management counters into non-negative interval deltas, uses the current cumulative values for an instance's first snapshot, and handles process resets.
- `blob-helper-dashboard/src/main/java/com/edem/blobhelper/dashboard/polling/InstancePollingService.java`: independently polls registered instances, persists snapshots/status, records failures, and runs retention cleanup.
- `blob-helper-dashboard/src/main/java/com/edem/blobhelper/dashboard/api/DashboardController.java`: exposes read-only overview, instance status, per-instance history, and seven-day failure resources.
- `blob-helper-dashboard/src/main/java/com/edem/blobhelper/dashboard/api/DashboardView.java`: provider-neutral JSON view records for dashboard summaries, trends, instances, and failures.
- `blob-helper-dashboard/src/main/resources/static/index.html`: responsive read-only operations console with overview, fleet, and failure views; the overview keeps avoided bytes as the single hero signal, and the latest-event label uses the dashboard blue accent.
- `blob-helper-dashboard/src/main/resources/static/css/dashboard.css`: CSS-variable light/dark theme, responsive layout, status states, accessible visual hierarchy, a balanced overview split, aligned nine-column fleet table sizing, and consistent settings-cell insets.
- `blob-helper-dashboard/src/main/resources/static/css/states.css`: dashboard state and configuration overrides, including the four-column desktop settings row and responsive two-column fallback.
- `blob-helper-dashboard/src/main/resources/static/js/dashboard.js`: fetches dashboard resources, renders metrics/instances/failures, draws the trend chart, persists theme choice locally, and tolerates optional/mismatched presentation selectors during refresh.
- `blob-helper-dashboard/src/main/resources/application.yaml`: dashboard defaults for loopback binding, port 9090, database path, polling interval, and failure retention.
- `.github/workflows/ci.yml`: GitHub Actions workflow that runs Maven verify on pushes, pull requests, and manual dispatch.
- `.github/dependabot.yml`: weekly Maven and GitHub Actions dependency update proposals.
- `.github/workflows/dependency-review.yml`: pull-request vulnerability gate configured to fail on high severity or above.
- `src/main/java/com/edem/blobhelper/BlobHelperApplication.java`: original Spring Boot application class. Current root packaging means this is not part of a normal Spring Boot app module.
- `docs/provider-testing.md`: documents credential-free provider contract coverage and the opt-in path for future external provider tests.

## Per-Module Breakdown

### Root Reactor

- **Entry point:** `pom.xml`
- **Key configuration:** Java 21, JUnit 5.13.4 BOM, Spring Boot 3.5.10 BOM, Maven Enforcer 3.6.3 dependency convergence, Maven Compiler Plugin 3.14.1, Maven Surefire Plugin 3.5.4.
- **Initialization:** Maven builds modules listed under `<modules>`.
- **Non-obvious logic:** Root no longer inherits `spring-boot-starter-parent`; it is a plain Maven parent POM.

### blob-helper-core

- **Entry point:** `blob-helper-core/pom.xml`
- **Key classes/functions:** `Sha256ContentHasher.hash(InputStream)` computes lowercase SHA-256 while reading a stream; `HashObjectKeyStrategy.generateKey(ContentHash)` generates deterministic hash-derived relative keys; `BlobStorage` defines provider-neutral storage operations; `PutBlobRequest`, `StoredBlob`, `BlobResource`, `StoreBlobCommand`, and `BlobReference` define the immutable streaming API boundary; `CoreModuleBoundaryTest` inspects classpath directories and JARs for forbidden package roots.
- **Initialization:** Built as Maven child of root `blob-helper`.
- **Non-obvious logic:** Object keys are derived from content identity, not user filenames. Empty key prefixes omit the leading prefix segment and still produce relative keys. Core request/result records reject invalid required fields and defensively copy metadata. `BlobResource` implements `AutoCloseable` and delegates closure to its stream. Storage adapters translate provider failures into unchecked `BlobHelperException` subtypes. Maven Enforcer also rejects forbidden direct and transitive dependency coordinates before tests run.

### blob-helper-jpa

- **Entry point:** `blob-helper-jpa/pom.xml`
- **Key classes/functions:** `AssetContent` maps `blob_helper_asset_content`; its public constructor validates required physical metadata, initializes new content with `refCount = 1`, and JPA lifecycle callbacks maintain creation/update timestamps. `AssetContentRepository.findByIdentity` queries the complete identity tuple, while `findByIdForUpdate` uses `LockModeType.PESSIMISTIC_WRITE`; `AssetContentMutationService.createOrRetain` returns an existing locked row or flushes a new insert and converts SQL state `23505` into `DuplicateContentIdentityException` for recovery after caller-owned rollback. `ReferenceCountService.retain` and `release` mutate the locked managed entity exactly once, reject missing or underflowed rows, and invoke the injected idempotent `BlobStorage.delete` collaborator only for the final reference. `ConcurrentUploadIntegrationTest` owns its resource-local rollback/retry and verifies one row with one reference per worker. Production code has no Hibernate or Spring imports; Hibernate and H2 are test-only dependencies.
- **Initialization:** Built as a Maven child of root `blob-helper`; consuming persistence environments discover the annotated entity, while tests bootstrap the `blob-helper-jpa-test` persistence unit directly.
- **Non-obvious logic:** Content identity is enforced by the database tuple `hash_algorithm + content_hash + size_bytes`. UUID generation and optimistic locking use standard Jakarta Persistence annotations. Repository lookups return `Optional` for missing rows, and locked lookups require the caller's active transaction to retain the database row lock. Reference-count mutation is package-private on the entity and exposed through the service, which throws core `ContentNotFoundException` and `ReferenceCountUnderflowException` before invalid state is persisted. Production code has no Hibernate or Spring imports; Hibernate and H2 are test-only dependencies.

### blob-helper-spring-boot-starter

- **Entry point:** `blob-helper-spring-boot-starter/pom.xml`
- **Key classes/functions:** `BlobHelperAutoConfiguration` registers `BlobHelperProperties` and a `BlobStorageProviderValidator` (`SmartInitializingSingleton`) that requires an explicit supported provider and exactly one storage bean by type. `LocalBlobStorageAutoConfiguration`, `S3BlobStorageAutoConfiguration`, and `AzureBlobStorageAutoConfiguration` bind provider settings and create the selected adapter, reusing application clients before constructing defaults. `BlobHelperProperties` binds `blob-helper.storage.provider`, `storage.key-prefix`, nested local/S3/Azure settings, deduplication hash and upload validation settings, and cleanup settings. `BlobDeduplicationService` exposes storage-neutral `store`, `retain`, `release`, and `get` operations using core models. `DefaultBlobDeduplicationService` buffers upload bytes, hashes them with `ContentHasher`, checks the complete identity tuple, retains existing rows through the lock-aware `ReferenceCountService`, and for new content generates a deterministic key through `ObjectKeyStrategy`, writes through `BlobStorage`, and persists `AssetContent` through `AssetContentMutationService`. `deduplication.max-upload-size` uses Spring Boot `DataSize` binding, so values such as `25MB` are accepted.
- **Initialization:** Built as a Maven child of root `blob-helper`; production code depends on the provider-neutral core/JPA modules and transitively aggregates the local, S3, and Azure adapter modules. Starter-owned provider configurations are registered through `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`, enable property binding independently, and run before the final validator. They construct clients without performing storage IO.
- **Non-obvious logic:** Reconciliation is disabled by default, physical deletion on zero references is enabled by default, and the starter has no REST controllers or direct provider SDK declarations. The auto-configured public service owns `REQUIRES_NEW` metadata transactions; callers constructing the raw delegate remain responsible for its transactions. Provider validation runs after singleton creation; supported selections are `local`, `s3`, and `azure`, including when supplying custom storage. A custom `BlobStorage` suppresses all default provider beans, so unused clients do not demand cloud settings. Spring manages S3 client shutdown and disables inferred destruction on its storage wrapper. Upload streams are buffered once so hashing and storage can consume equivalent byte sequences; duplicate matches reuse the persisted object key and content ID. `GenericStarterDependencyTest` proves all adapter classes are available from the starter test classpath while management/dashboard classes remain absent, and `ProviderDependencyBoundaryTest` proves SDK declarations stay in adapter POMs.

### blob-helper-storage-local

- **Entry point:** `blob-helper-storage-local/pom.xml`
- **Key classes/functions:** `LocalBlobStorageProperties` carries the local provider root directory; it defaults to `blob-helper-storage`, accepts any custom `Path`, and rejects null assignment. `LocalBlobStorage` implements the core `BlobStorage` SPI against that root: `put` streams into a same-directory temporary file, then publishes to `{root}/{objectKey}` through atomic replacement (falling back to ordinary replacement only when atomic moves are unsupported). This preserves existing content when an input stream fails and supports concurrent publication from separate adapter instances. Temporary names use a short prefix to support maximum-length target filenames. `get` returns a closeable `BlobResource` or throws core `ContentNotFoundException` when absent; `delete` is idempotent through `Files.deleteIfExists`; `exists` delegates to filesystem checks.
- **Initialization:** Built as a Maven child of root `blob-helper`; it depends only on the provider-neutral core module with no Spring or cloud SDK dependencies.
- **Non-obvious logic:** Keys resolve against an absolute normalized root; resolution then normalizes again and rejects results outside or equal to the root using component-based `startsWith` comparison, which defeats `..`, absolute-path, and prefix-collision escapes before any file IO. Storage failures translate to unchecked core `BlobStorageException`; missing reads use `ContentNotFoundException`. Content type and metadata are not persisted by the local adapter.

### blob-helper-storage-s3

- **Entry point:** `blob-helper-storage-s3/pom.xml`
- **Key classes/functions:** `S3BlobStorageProperties` carries the S3 bucket, region, optional endpoint override, and path-style access flag. `S3BlobStorage` implements streaming put/get, idempotent delete, `headObject`-based exists, client construction, and provider exception translation.
- **Initialization:** Built as a Maven child of root `blob-helper`; it depends on `blob-helper-core` and imports the AWS SDK v2 BOM locally so AWS dependencies remain isolated to this provider module.
- **Non-obvious logic:** The optional endpoint override and path-style access setting support S3-compatible targets such as emulators without affecting core or starter APIs. Reads return the SDK response stream directly and therefore require callers to close `BlobResource`; provider 404 responses become `ContentNotFoundException` for reads and `false` for existence checks.

### blob-helper-storage-azure

- **Entry point:** `blob-helper-storage-azure/pom.xml`
- **Key classes/functions:** `AzureBlobStorageProperties` carries the Azure container, connection string, optional endpoint, and account name. `AzureBlobStorage` implements the core `BlobStorage` SPI using an injected or builder-created `BlobContainerClient`; its public static `createClient` factory shares client construction with starter auto-configuration. `put` streams content with Azure HTTP headers and metadata, `get` reads blob properties before returning an owner-managed stream, `delete` uses `deleteIfExists`, and `exists` delegates to the provider.
- **Initialization:** Built as a Maven child of root `blob-helper`; it depends on `blob-helper-core`, imports the Azure SDK BOM version `1.3.8` locally, and declares `azure-storage-blob` without a version so Azure dependencies remain isolated to this provider module.
- **Non-obvious logic:** Azure 404 responses map to core `ContentNotFoundException` for reads and `false` for existence checks; other Azure provider failures become core `BlobStorageException`. The contract test uses the real Azure SDK against an in-process JDK HTTP server, so normal verification needs no Azure credentials or external service.

### blob-helper-spring-boot-management

- **Entry point:** `blob-helper-spring-boot-management/pom.xml`
- **Key classes/functions:** `BlobHelperManagementProperties` controls opt-in management identity and base path; `BlobHelperManagementController` exposes `/blob-helper/management/v1/info`, `/health`, `/metrics`, and `/failures` as read-only provider-neutral JSON.
- **Initialization:** Optional Spring Boot auto-configuration is registered through `AutoConfiguration.imports`; management is disabled unless explicitly enabled.
- **Non-obvious logic:** Missing Micrometer meters and metadata repositories produce safe zero totals, and failure details are supplied through an optional provider-neutral source. The module does not receive S3/Azure credentials.

### blob-helper-dashboard

- **Entry point:** `blob-helper-dashboard/src/main/java/com/edem/blobhelper/dashboard/BlobHelperDashboardApplication.java`
- **Key classes/functions:** `BlobHelperDashboardApplication`, persistence/polling services, `DashboardController`, and `DashboardView` provide persistent collection and read-only JSON views; the static UI renders overview, instances, trend, and failure states with the demo console's slate/amber/blue visual language and a manual light/dark theme toggle. The failures endpoint names its optional `since` request parameter explicitly for Spring MVC binding.
- **Initialization:** Standalone Spring Boot application defaults to `127.0.0.1:9090`; it stores its own registry and history in a configurable SQLite file.
- **Non-obvious logic:** Poll failures are isolated per instance; counter resets after an instance restart never produce negative deltas; detailed failures expire after seven days while aggregate snapshots remain. `MultiInstanceDashboardIntegrationTest` validates the complete local flow with two in-process management endpoints and temporary SQLite storage.

### Provider Testing

- **Entry point:** `docs/provider-testing.md`
- **Key behavior:** Default verification runs the POM/classpath boundary checks and in-process S3/Azure contract tests without credentials. Future credential-dependent tests must be tagged `external-provider` and run through Surefire's explicit tag selector.

### Documentation and Planning

- **Entry point:** `docs/taskindex.md`
- **Key files:** `docs/SPECIFICATION.md`, `docs/adrs/*.md`, `docs/implementation-plans/*.md`, `docs/epics/**/tasks/*.md`.
- **Initialization:** Manual planning docs drive future implementation tasks.
- **Non-obvious logic:** `docs/taskindex.md` is the status board. Epic 1 is complete and Tasks 2.1 and 2.2 are the completed Epic 2 tasks.

### CI and supply-chain governance

- **Entry point:** `.github/workflows/ci.yml`
- **Key behavior:** Checks out code, sets up Java 21 Temurin, enables Maven dependency caching, runs `./mvnw --batch-mode --no-transfer-progress verify`, and uploads Surefire reports.
- **Initialization:** Triggered on pushes and pull requests targeting `main`, `staging`, or `dev`, and by manual `workflow_dispatch`.
- **Non-obvious logic:** The workflow runs `chmod +x ./mvnw` so CI is not blocked if executable bits are lost.
- **Dependency safeguards:** The root Enforcer plugin checks convergence for Netty, Jackson, Reactor, HTTP Components, and SLF4J. Dependabot proposes weekly Maven/Actions updates, while `dependency-review.yml` fails pull requests that introduce high- or critical-severity vulnerabilities.

## Configuration

| Variable / Property | Default | Purpose |
|---|---|---|
| `spring.application.name` | `blob-helper` | Present in root `src/main/resources/application.yaml`. |
| `java.version` | `21` | Maven compiler release target. |
| `junit.version` | `5.13.4` | JUnit BOM version. |
| `spring-boot.version` | `3.5.10` | Spring Boot BOM version used by the starter module. |
| `maven-enforcer.version` | `3.6.3` | Maven Enforcer version used for reactor dependency-convergence validation. |

Implemented starter properties:

| Property | Default | Purpose |
|---|---|---|
| `blob-helper.storage.provider` | None (required) | Selects `s3`, `azure`, or `local`; required even for application-provided storage. |
| `blob-helper.persistence.initialize-schema` | `embedded` | Initialize embedded databases only; `always` explicitly allows external initialization; `never` validates pre-existing schema without Blob Helper DDL. |
| `blob-helper.storage.key-prefix` | Empty | Prefix for generated object keys. |
| `blob-helper.storage.local.root-directory` | `blob-helper-storage` | Filesystem root for auto-configured local storage. |
| `blob-helper.storage.s3.bucket` | None | Required for default S3 storage. |
| `blob-helper.storage.s3.region` | AWS region chain | Optional explicit AWS region. |
| `blob-helper.storage.s3.endpoint` | SDK default | Optional S3-compatible endpoint URI. |
| `blob-helper.storage.s3.path-style` | `false` | Forces path-style S3 addressing. |
| `blob-helper.storage.azure.container` | None | Required for default Azure storage. |
| `blob-helper.storage.azure.connection-string` | None | Azure client connection string; alternatively supply an endpoint or application client. |
| `blob-helper.storage.azure.endpoint` | None | Azure service endpoint URI, also usable as a connection-string endpoint override. |
| `blob-helper.storage.azure.account-name` | None | Passed to adapter properties; the existing client factory does not use it for authentication. |
| `blob-helper.deduplication.hash-algorithm` | `SHA-256` | Content identity hash algorithm. |
| `blob-helper.deduplication.max-upload-size` | `25MB` | Maximum upload size. |
| `blob-helper.deduplication.strict-content-type-validation` | `false` | Rejects unsupported content types when enabled. |
| `blob-helper.cleanup.delete-physical-on-zero-references` | `true` | Controls physical deletion after the final reference is released. |
| `blob-helper.cleanup.reconciliation-enabled` | `false` | Controls reconciliation scheduling; disabled by default. |
| `blob-helper.management.enabled` | `false` | Enables the local read-only management API in a consuming application. |
| `blob-helper.management.base-path` | `/blob-helper/management` | Base path for local management endpoints. |
| `blob-helper.dashboard-registration.enabled` | `false` | Enables asynchronous self-registration with the local dashboard. |
| `blob-helper.dashboard-registration.dashboard-url` | None | Local dashboard registration URL. |
| `blob-helper.dashboard-registration.instance-name` | `blob-helper` | Display name shown in the dashboard. |
| `blob-helper.dashboard-registration.advertised-url` | None | Management URL the dashboard polls. |
| `blob-helper.dashboard-registration.instance-id` | Generated | Optional UUID; otherwise derived stably from instance name and advertised URL. |

Dashboard settings:

| Property | Default | Purpose |
|---|---|---|
| `server.address` | `127.0.0.1` | Local-only dashboard bind address. |
| `server.port` | `9090` | Dedicated dashboard port. |
| `blob-helper.dashboard.database-path` | `./blob-helper-dashboard.sqlite` | SQLite file location. |
| `blob-helper.dashboard.polling-interval` | `30s` | Fixed delay between instance polls. |
| `blob-helper.dashboard.failure-retention` | `7d` | Detailed failure retention period; aggregate snapshots are retained. |
