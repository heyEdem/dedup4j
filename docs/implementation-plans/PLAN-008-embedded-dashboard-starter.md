# Embedded Dashboard Starter Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a Spring Boot application serve the Blob Helper dashboard UI automatically when an optional dashboard dependency is added, without requiring a second dashboard process.

**Architecture:** Add a new `dedup4j-spring-boot-dashboard` starter that auto-configures a same-application read-only dashboard at `/dedup4j/dashboard`. It will expose current-instance metrics through dashboard-shaped REST resources and serve the existing static console from the consuming application. The existing `dedup4j-dashboard` application remains available for multi-instance fleet monitoring and is not activated by the starter.

**Tech Stack:** Java 21, Spring Boot 3.5.10, Spring MVC, Spring Boot auto-configuration, Micrometer, Jakarta Persistence, vanilla HTML/CSS/JavaScript, JUnit 5, Spring Boot test, AssertJ, Maven.

**Spec:** `docs/SPECIFICATION.md` and the approved user requirement: adding the optional dashboard dependency and starting the host Spring Boot application must make the dashboard UI available automatically.

## Global Constraints

- Keep the dashboard read-only; it must not add blob mutation or repair endpoints.
- Keep provider SDKs isolated in `dedup4j-storage-s3` and `dedup4j-storage-azure`.
- Keep the existing `dedup4j-spring-boot-starter` free of dashboard controllers and UI resources.
- The dashboard must be disabled explicitly with `dedup4j.dashboard.enabled=false`.
- The embedded UI default route is `/dedup4j/dashboard`.
- Embedded dashboard API routes are under `/dedup4j/dashboard/api/v1`.
- The standalone dashboard remains a separate executable for multi-instance monitoring.
- Every implementation task must add or update focused tests before implementation code.
- Run `./mvnw --batch-mode --no-transfer-progress verify` before claiming completion.
- Do not publish `dedup4j-dashboard` as a library dependency; publish the new dashboard starter as an optional library artifact.

---

## File and module map

### New embedded dashboard module

- Create: `dedup4j-spring-boot-dashboard/pom.xml`
- Create: `dedup4j-spring-boot-dashboard/src/main/java/com/edem/dedup4j/dashboard/autoconfigure/Dedup4jDashboardProperties.java`
- Create: `dedup4j-spring-boot-dashboard/src/main/java/com/edem/dedup4j/dashboard/autoconfigure/Dedup4jDashboardAutoConfiguration.java`
- Create: `dedup4j-spring-boot-dashboard/src/main/java/com/edem/dedup4j/dashboard/api/EmbeddedDashboardController.java`
- Create: `dedup4j-spring-boot-dashboard/src/main/java/com/edem/dedup4j/dashboard/api/EmbeddedDashboardView.java`
- Create: `dedup4j-spring-boot-dashboard/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Create: `dedup4j-spring-boot-dashboard/src/main/resources/static/dedup4j/dashboard/index.html`
- Create: `dedup4j-spring-boot-dashboard/src/main/resources/static/dedup4j/dashboard/css/dashboard.css`
- Create: `dedup4j-spring-boot-dashboard/src/main/resources/static/dedup4j/dashboard/css/states.css`
- Create: `dedup4j-spring-boot-dashboard/src/main/resources/static/dedup4j/dashboard/js/dashboard.js`
- Create: `dedup4j-spring-boot-dashboard/src/test/java/com/edem/dedup4j/dashboard/autoconfigure/Dedup4jDashboardAutoConfigurationTest.java`
- Create: `dedup4j-spring-boot-dashboard/src/test/java/com/edem/dedup4j/dashboard/api/EmbeddedDashboardControllerTest.java`
- Create: `dedup4j-spring-boot-dashboard/src/test/java/com/edem/dedup4j/dashboard/EmbeddedDashboardIntegrationTest.java`

### Existing project files

- Modify: `pom.xml` to include the new module and manage its shared version.
- Modify: `dedup4j-spring-boot-management/pom.xml` only if a shared snapshot service is extracted.
- Modify: `dedup4j-dashboard/pom.xml` only if static resource ownership is moved to a shared resource module.
- Modify: `docs/architecture.md` and `docs/implementation.md` for the new starter and changed dashboard roles.
- Create: `docs/adrs/ADR-006-embedded-dashboard-starter.md` documenting the decision to make the embedded dashboard primary.
- Modify: `docs/SPECIFICATION.md` to distinguish embedded single-instance UI from the standalone fleet dashboard.
- Modify: `docs/taskindex.md` when the approved implementation tasks are added to the project status board.
- Modify: `docs/changelog.md` after implementation is complete.

## Route and behavior contract

The starter must provide these routes in a consuming Spring Boot application:

```text
GET /dedup4j/dashboard
    Serves the dashboard index page.

GET /dedup4j/dashboard/api/v1/overview
    Returns current-instance aggregate metrics.

GET /dedup4j/dashboard/api/v1/instances/status
    Returns one instance row representing the host application.

GET /dedup4j/dashboard/api/v1/instances/{id}/history
    Returns current-process history if history storage is implemented; otherwise
    returns an empty points list with the stable instance ID.

GET /dedup4j/dashboard/api/v1/failures
    Returns recent failures supplied by the optional failure source.
```

The UI JavaScript must use a configurable relative API base so the same visual
console can be served under `/dedup4j/dashboard` without hard-coded host
names or a second process.

## Tasks

### Task 1: Record the embedded-dashboard architecture decision

**Files:**

- Create: `docs/adrs/ADR-006-embedded-dashboard-starter.md`
- Modify: `docs/SPECIFICATION.md`
- Modify: `docs/taskindex.md`

**Interfaces:**

- Consumes: current standalone dashboard behavior documented in `PLAN-007` and `ADR-005`.
- Produces: an explicit distinction between the embedded dashboard for one application and the standalone dashboard for multiple registered applications.

- [ ] **Step 1: Write the ADR**

Document the context, decision, alternatives, and consequences. The decision must state that the optional Spring Boot dashboard starter is the primary developer experience and that the standalone dashboard remains a separate fleet-monitoring application.

- [ ] **Step 2: Update the specification**

Change the dashboard requirements so they cover both modes:

```text
Embedded mode: add the dashboard starter and open /dedup4j/dashboard.
Standalone mode: run the dashboard application separately to aggregate multiple instances.
```

- [ ] **Step 3: Add the feature to the task index**

Add a new planned feature section with the embedded dashboard tasks and leave the existing completed standalone dashboard tasks unchanged.

- [ ] **Step 4: Review the decision**

Confirm that the decision does not move business REST controllers into the core starter and does not grant the dashboard write access.

- [ ] **Step 5: Commit**

```bash
git add docs/adrs/ADR-006-embedded-dashboard-starter.md docs/SPECIFICATION.md docs/taskindex.md
git commit -m "docs: decide embedded dashboard starter architecture"
```

### Task 2: Add the dashboard starter module and properties

**Files:**

- Modify: `pom.xml`
- Create: `dedup4j-spring-boot-dashboard/pom.xml`
- Create: `dedup4j-spring-boot-dashboard/src/main/java/com/edem/dedup4j/dashboard/autoconfigure/Dedup4jDashboardProperties.java`
- Create: `dedup4j-spring-boot-dashboard/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Test: `dedup4j-spring-boot-dashboard/src/test/java/com/edem/dedup4j/dashboard/autoconfigure/Dedup4jDashboardPropertiesTest.java`

**Interfaces:**

- Consumes: `dedup4j-core`, `dedup4j-jpa`, and `dedup4j-spring-boot-starter`.
- Produces: `com.edem.dedup4j.dashboard.autoconfigure.Dedup4jDashboardProperties` with `enabled`, `basePath`, and `failureLookback` properties.

The default property values must be:

```text
dedup4j.dashboard.enabled=true
dedup4j.dashboard.base-path=/dedup4j/dashboard
dedup4j.dashboard.failure-lookback=7d
```

- [ ] **Step 1: Add the failing properties test**

Verify defaults and relaxed binding for:

```java
assertThat(properties.isEnabled()).isTrue();
assertThat(properties.getBasePath()).isEqualTo("/dedup4j/dashboard");
assertThat(properties.getFailureLookback()).isEqualTo(Duration.ofDays(7));
```

- [ ] **Step 2: Run the focused test and verify it fails**

Run: `./mvnw -pl dedup4j-spring-boot-dashboard test -Dtest=Dedup4jDashboardPropertiesTest`

Expected: compilation failure because the new module and properties class do not exist.

- [ ] **Step 3: Add the module and properties class**

Use Spring Boot configuration binding with a `Duration` field and normalize the base path to begin with `/` and omit a trailing `/`. The module must not depend on `spring-boot-starter-web`; depend on the minimal Spring Boot autoconfiguration and Spring MVC APIs needed by the starter.

- [ ] **Step 4: Register auto-configuration**

Add exactly:

```text
com.edem.dedup4j.dashboard.autoconfigure.Dedup4jDashboardAutoConfiguration
```

to `AutoConfiguration.imports`.

- [ ] **Step 5: Run the focused test and verify it passes**

Run: `./mvnw -pl dedup4j-spring-boot-dashboard test -Dtest=Dedup4jDashboardPropertiesTest`

- [ ] **Step 6: Commit**

```bash
git add pom.xml dedup4j-spring-boot-dashboard
git commit -m "feat: add embedded dashboard starter module"
```

### Task 3: Extract a reusable current-instance metrics snapshot

**Files:**

- Create: `dedup4j-spring-boot-dashboard/src/main/java/com/edem/dedup4j/dashboard/api/EmbeddedDashboardSnapshotService.java`
- Create: `dedup4j-spring-boot-dashboard/src/main/java/com/edem/dedup4j/dashboard/api/EmbeddedDashboardFailureSource.java` only when an application failure source is available through the existing contract.
- Test: `dedup4j-spring-boot-dashboard/src/test/java/com/edem/dedup4j/dashboard/api/EmbeddedDashboardSnapshotServiceTest.java`

**Interfaces:**

- Consumes: `MeterRegistry`, optional `AssetContentRepository`, `Dedup4jProperties`, and the existing management `FailureSource` contract where available.
- Produces: immutable current-instance values used by `EmbeddedDashboardController`.

The snapshot service must calculate the same metric meanings as the existing
management API:

```text
uploads                  = dedup4j.uploads counter
duplicates               = dedup4j.duplicates counter
physicalUploads          = dedup4j.skipped.physical.writes counter
logicalBytes             = dedup4j.bytes.accepted counter
avoidedBytes             = dedup4j.bytes.avoided counter
contentCount             = number of AssetContent rows, or 0 when unavailable
physicalBytes            = sum of AssetContent.sizeBytes, or 0 when unavailable
newUploads               = uploads - duplicates, never below 0
duplicateRate            = duplicates / uploads, or 0 when uploads is 0
```

- [ ] **Step 1: Write tests for complete metrics and absent optional collaborators**

Cover a populated registry/repository and a context with no meter registry or repository. Assert that absent collaborators produce zero-safe values and that `newUploads` never becomes negative.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./mvnw -pl dedup4j-spring-boot-dashboard test -Dtest=EmbeddedDashboardSnapshotServiceTest`

Expected: compilation failure because the snapshot service does not exist.

- [ ] **Step 3: Implement the service**

Use `ObjectProvider` for optional collaborators. Read each counter by exact metric name and return immutable records. Do not call the existing web controller from the new service.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./mvnw -pl dedup4j-spring-boot-dashboard test -Dtest=EmbeddedDashboardSnapshotServiceTest`

- [ ] **Step 5: Commit**

```bash
git add dedup4j-spring-boot-dashboard/src/main/java dedup4j-spring-boot-dashboard/src/test/java
git commit -m "feat: add embedded dashboard metrics snapshot"
```

### Task 4: Add the embedded dashboard API and auto-configuration

**Files:**

- Create: `dedup4j-spring-boot-dashboard/src/main/java/com/edem/dedup4j/dashboard/api/EmbeddedDashboardView.java`
- Create: `dedup4j-spring-boot-dashboard/src/main/java/com/edem/dedup4j/dashboard/api/EmbeddedDashboardController.java`
- Modify: `dedup4j-spring-boot-dashboard/src/main/java/com/edem/dedup4j/dashboard/autoconfigure/Dedup4jDashboardAutoConfiguration.java`
- Test: `dedup4j-spring-boot-dashboard/src/test/java/com/edem/dedup4j/dashboard/api/EmbeddedDashboardControllerTest.java`
- Test: `dedup4j-spring-boot-dashboard/src/test/java/com/edem/dedup4j/dashboard/autoconfigure/Dedup4jDashboardAutoConfigurationTest.java`

**Interfaces:**

- Consumes: `EmbeddedDashboardSnapshotService`, `Dedup4jDashboardProperties`, and the optional failure source.
- Produces: read-only JSON routes under `${dedup4j.dashboard.base-path}/api/v1`.

The controller must use the same field meanings as the standalone `DashboardView` records so the static UI can render either mode. For a single host application, return exactly one instance row with the configured management instance ID/name and provider.

- [ ] **Step 1: Write MVC controller tests**

Verify:

```java
mockMvc.perform(get("/dedup4j/dashboard/api/v1/overview"))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.instanceCount").value(1))
       .andExpect(jsonPath("$.uploads").value(15));

mockMvc.perform(get("/dedup4j/dashboard/api/v1/failures"))
       .andExpect(status().isOk());
```

Also verify that POST/PUT/DELETE requests are not mapped and that the configured base path is honored.

- [ ] **Step 2: Run controller tests to verify they fail**

Run: `./mvnw -pl dedup4j-spring-boot-dashboard test -Dtest=EmbeddedDashboardControllerTest`

Expected: failure because the controller and routes do not exist.

- [ ] **Step 3: Implement the view records and controller**

Implement overview, one-instance status, empty-or-current history, and recent failures. Keep the controller read-only and map the optional `since` parameter explicitly as `@RequestParam(name = "since", required = false)`.

- [ ] **Step 4: Write auto-configuration tests**

Verify:

```text
dashboard enabled by default when the module is on a web application classpath
dashboard disabled when dedup4j.dashboard.enabled=false
custom base path changes both UI and API routes
dashboard does not require dashboard-registration properties
```

- [ ] **Step 5: Implement conditional auto-configuration**

Use `@ConditionalOnWebApplication` and `@ConditionalOnClass` for Spring MVC. Register properties, snapshot service, controller, and resource-serving configuration only when enabled. Do not create an embedded controller in the standalone dashboard application.

- [ ] **Step 6: Run focused tests to verify they pass**

Run: `./mvnw -pl dedup4j-spring-boot-dashboard test -Dtest=EmbeddedDashboardControllerTest,Dedup4jDashboardAutoConfigurationTest`

- [ ] **Step 7: Commit**

```bash
git add dedup4j-spring-boot-dashboard/src/main/java dedup4j-spring-boot-dashboard/src/test/java
git commit -m "feat: serve embedded dashboard API"
```

### Task 5: Serve the static UI from the consuming application

**Files:**

- Create: `dedup4j-spring-boot-dashboard/src/main/resources/static/dedup4j/dashboard/index.html`
- Create: `dedup4j-spring-boot-dashboard/src/main/resources/static/dedup4j/dashboard/css/dashboard.css`
- Create: `dedup4j-spring-boot-dashboard/src/main/resources/static/dedup4j/dashboard/css/states.css`
- Create: `dedup4j-spring-boot-dashboard/src/main/resources/static/dedup4j/dashboard/js/dashboard.js`
- Modify: `dedup4j-spring-boot-dashboard/src/main/java/com/edem/dedup4j/dashboard/api/EmbeddedDashboardController.java`
- Test: `dedup4j-spring-boot-dashboard/src/test/java/com/edem/dedup4j/dashboard/EmbeddedDashboardIntegrationTest.java`

**Interfaces:**

- Consumes: embedded API routes from Task 4.
- Produces: browser UI at `/dedup4j/dashboard` with relative API requests.

- [ ] **Step 1: Write the static UI integration test**

Start a minimal Spring Boot test application with the dashboard starter and verify:

```java
mockMvc.perform(get("/dedup4j/dashboard"))
       .andExpect(status().isOk())
       .andExpect(content().string(containsString("Make every byte count.")));

mockMvc.perform(get("/dedup4j/dashboard/js/dashboard.js"))
       .andExpect(status().isOk())
       .andExpect(content().string(containsString("/api/v1/overview")));
```

The test must also verify that the API returns the same metrics to the UI and that no SQLite database is created by the embedded starter.

- [ ] **Step 2: Run the integration test to verify it fails**

Run: `./mvnw -pl dedup4j-spring-boot-dashboard test -Dtest=EmbeddedDashboardIntegrationTest`

Expected: 404 because the embedded static resources are not present.

- [ ] **Step 3: Move the visual console into the embedded resource path**

Reuse the approved dashboard visual design and fix the browser paths so the script fetches relative resources from:

```text
./api/v1/overview
./api/v1/instances/status
./api/v1/failures
```

The dashboard root controller must forward `/dedup4j/dashboard` to the packaged `index.html` resource.

- [ ] **Step 4: Add the static resource and root route configuration**

Serve the resource directory under the configured base path and ensure the root route works with or without a trailing slash. Preserve the existing light/dark theme, responsive layout, table alignment, and defensive DOM rendering fixes.

- [ ] **Step 5: Run the integration test to verify it passes**

Run: `./mvnw -pl dedup4j-spring-boot-dashboard test -Dtest=EmbeddedDashboardIntegrationTest`

- [ ] **Step 6: Commit**

```bash
git add dedup4j-spring-boot-dashboard/src/main/resources dedup4j-spring-boot-dashboard/src/main/java dedup4j-spring-boot-dashboard/src/test/java
git commit -m "feat: embed dashboard UI in Spring Boot applications"
```

### Task 6: Verify standalone dashboard compatibility and multi-instance behavior

**Files:**

- Modify: `dedup4j-dashboard/src/main/resources/static/js/dashboard.js` only if the shared UI path change requires it.
- Modify: `dedup4j-dashboard/pom.xml` only if the standalone app consumes a shared UI resource artifact.
- Test: `dedup4j-dashboard/src/test/java/com/edem/dedup4j/dashboard/MultiInstanceDashboardIntegrationTest.java`
- Test: `dedup4j-spring-boot-management/src/test/java/com/edem/dedup4j/management/ManagementDashboardContractTest.java`

**Interfaces:**

- Consumes: existing management registration and standalone polling contracts.
- Produces: regression evidence that the new embedded mode does not break fleet mode.

- [ ] **Step 1: Run existing standalone and management tests before changes**

Run: `./mvnw -pl dedup4j-spring-boot-management,dedup4j-dashboard test`

Record the passing baseline in the task notes.

- [ ] **Step 2: Add a two-mode integration assertion**

Verify that a host application with the embedded starter serves its own dashboard without registering with a standalone dashboard, while the existing management module still self-registers only when dashboard registration is explicitly enabled.

- [ ] **Step 3: Implement only required compatibility changes**

Do not merge SQLite polling, instance registration, or multi-instance persistence into the embedded starter. The embedded mode represents the current process; the standalone mode aggregates registered processes.

- [ ] **Step 4: Run the regression suite**

Run: `./mvnw -pl dedup4j-spring-boot-management,dedup4j-dashboard test`

- [ ] **Step 5: Commit**

```bash
git add dedup4j-dashboard dedup4j-spring-boot-management
git commit -m "test: preserve standalone dashboard monitoring mode"
```

### Task 7: Add consumer-facing documentation and Maven artifact policy

**Files:**

- Create or modify in `/Users/Edem/Documents/IdeaProjects/blob-helper-docs/docs/`: `getting-started/installation.md`, `getting-started/quick-start.md`, `guides/embedded-dashboard.md`, and `releases.md`.
- Modify: `/Users/Edem/Documents/blob-helper-maven-central-roadmap.md` if the artifact list changes.
- Modify: `README.md` in the Java repository with the embedded dashboard dependency and route.
- Modify: `docs/architecture.md`, `docs/implementation.md`, and `docs/changelog.md`.

**Interfaces:**

- Consumes: final Maven coordinates and tested route/property names from Tasks 2–5.
- Produces: installation documentation that a clean Spring Boot consumer can follow.

- [ ] **Step 1: Write the installation example**

Document the dependency using the confirmed release namespace and version. The example must include the main starter and optional dashboard starter, then show:

```text
start the application
open http://localhost:8080/dedup4j/dashboard
```

- [ ] **Step 2: Document configuration**

Explain the default-on behavior and opt-out:

```yaml
blob-helper:
  dashboard:
    enabled: false
```

- [ ] **Step 3: Document embedded versus standalone modes**

Use a comparison table explaining that embedded mode needs no separate process and displays one application, while standalone mode is used for multiple registered instances and SQLite history.

- [ ] **Step 4: Update Java project indexes**

Add the new module, routes, properties, and auto-configuration to `docs/architecture.md` and `docs/implementation.md`. Add the implementation entry to `docs/changelog.md` after verification.

- [ ] **Step 5: Verify documentation links and snippets**

Check that every dependency artifact and configuration key in the docs matches the final POMs and Java properties.

- [ ] **Step 6: Commit**

```bash
git add README.md docs
git commit -m "docs: document embedded dashboard usage"
```

### Task 8: Run full verification and prepare the release boundary

**Files:**

- Modify: `.github/workflows/ci.yml` if the new module requires a separate static-resource or consumer smoke check.
- Modify: `docs/taskindex.md` to mark the feature complete only after all tests pass.

**Interfaces:**

- Consumes: all completed module, API, UI, compatibility, and documentation tasks.
- Produces: verified feature ready for Maven Central inclusion as an optional dashboard starter.

- [ ] **Step 1: Run formatting and static checks**

Run:

```bash
git diff --check
node --check dedup4j-spring-boot-dashboard/src/main/resources/static/dedup4j/dashboard/js/dashboard.js
```

- [ ] **Step 2: Run the complete reactor verification**

Run:

```bash
./mvnw --batch-mode --no-transfer-progress verify
```

Expected: all existing tests and all embedded dashboard tests pass.

- [ ] **Step 3: Verify a clean consumer application**

Build a temporary Spring Boot consumer that declares only:

```xml
<dependency>
    <groupId>io.github.heyEdem</groupId>
    <artifactId>dedup4j-spring-boot-starter</artifactId>
    <version>...</version>
</dependency>
<dependency>
    <groupId>io.github.heyEdem</groupId>
    <artifactId>dedup4j-spring-boot-dashboard</artifactId>
    <version>...</version>
</dependency>
```

Start it and verify `/dedup4j/dashboard`, `/dedup4j/dashboard/api/v1/overview`, and the static JavaScript resource all return successfully.

- [ ] **Step 4: Re-scan changed files and direct neighbors**

Run:

```bash
git diff HEAD~1 --name-only
```

Read the changed module POM, auto-configuration imports, controllers, static resource files, tests, and their same-directory neighbors. Update only the affected index sections.

- [ ] **Step 5: Check the architectural-decision gate**

Confirm that ADR-006 records the reversal from standalone-only user experience to embedded-primary user experience, while preserving the standalone fleet mode.

- [ ] **Step 6: Commit the verified milestone**

```bash
git add .
git commit -m "feat: add embedded Blob Helper dashboard starter"
```

## Acceptance criteria

- Adding `dedup4j-spring-boot-dashboard` to a Spring Boot application makes `/dedup4j/dashboard` available after application startup.
- No second Java process, SQLite file, registration URL, or dashboard application is required for embedded mode.
- The embedded UI shows current uploads, duplicates, logical bytes, physical bytes, avoided bytes, instance status, and failures when available.
- `dedup4j.dashboard.enabled=false` disables the UI and all embedded dashboard routes.
- The embedded API is GET-only and does not mutate blobs, metadata, or reference counts.
- Existing standalone multi-instance dashboard tests continue to pass.
- A clean consumer project can resolve the dashboard starter as a Maven dependency.
- The Maven Central documentation clearly explains embedded mode and standalone fleet mode.
