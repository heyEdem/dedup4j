# Combined Observability Starter Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a consumer install the embedded dashboard and instance management API with one optional dependency while keeping upload-only applications and the standalone fleet dashboard unchanged.

**Architecture:** A new empty-code aggregate JAR depends directly on the existing management and embedded-dashboard modules. Spring Boot discovers the existing auto-configurations from those dependency JARs; no duplicate controllers or forwarding configuration are introduced. Existing properties continue to control activation.

**Tech Stack:** Maven multi-module reactor, Spring Boot auto-configuration, `WebApplicationContextRunner`, JUnit 5, AssertJ.

**Implements:** ADR-010

---

## Five-Questions Contract

- **Protected outcome (Q1):** one optional `dedup4j-spring-boot-observability` dependency installs the single-application management and embedded-dashboard experience.
- **Invariants (Q2):** generic starter stays upload-only, routes stay read-only, dashboard default remains enabled when installed, management remains explicitly enabled, standalone fleet monitoring stays separate.
- **Owner (Q3):** the aggregate module owns dependency assembly only; existing modules retain all implementation and properties.
- **Proof (Q4):** classpath, activation, disablement, dependency-boundary, and full-reactor tests below.
- **Exclusions (Q5):** no source merge, route changes, security-policy change, standalone dashboard inclusion, or Git operation.

## File Map

- Modify: `pom.xml` — add the aggregate reactor module.
- Create: `dedup4j-spring-boot-observability/pom.xml`.
- Create: `dedup4j-spring-boot-observability/src/test/java/com/edem/dedup4j/observability/ObservabilityStarterClasspathTest.java`.
- Create: `dedup4j-spring-boot-observability/src/test/java/com/edem/dedup4j/observability/ObservabilityStarterContextTest.java`.
- Modify: `dedup4j-spring-boot-starter/src/test/java/com/edem/dedup4j/autoconfigure/GenericStarterDependencyTest.java` — include the aggregate artifact in the exclusion boundary.
- Modify after implementation: `README.md`.
- Modify after implementation: `docs/architecture.md`.
- Modify after implementation: `docs/implementation.md`.
- Modify after implementation: `docs/changelog.md`.

## Acceptance Criteria (from Q4)

- [ ] **ObservabilityStarterClasspathTest.includesManagementAndEmbeddedDashboard:** both existing auto-configuration classes and dashboard static resource are loadable from the aggregate module.
- [ ] **ObservabilityStarterClasspathTest.excludesStandaloneDashboard:** standalone application/persistence classes are absent.
- [ ] **ObservabilityStarterContextTest.dashboardIsEnabledByDefault:** a servlet context creates the embedded dashboard controller when no dashboard property is supplied.
- [ ] **ObservabilityStarterContextTest.managementRemainsDisabledByDefault:** installing the aggregate alone does not expose the management controller.
- [ ] **ObservabilityStarterContextTest.enablesManagementExplicitly:** `dedup4j.management.enabled=true` creates the management controller.
- [ ] **ObservabilityStarterContextTest.canDisableBoth:** explicit false properties create neither controller.
- [ ] **ObservabilityStarterContextTest.routesAreReadOnly:** every management and embedded-dashboard mapping is GET/HEAD-only; no POST, PUT, PATCH, or DELETE route exists.
- [ ] **GenericStarterDependencyTest.excludesObservabilityModules:** the ordinary upload starter still has no management, dashboard, aggregate, or standalone dashboard dependency.

## Out of Scope (from Q5)

- Combining Java source packages from the two existing modules.
- Changing endpoint paths, response models, or read-only behavior.
- Enabling management endpoints by default.
- Bundling the standalone `dedup4j-dashboard` process or its SQLite database.
- Authentication/authorization additions.
- Git operations without Edem's instruction.

## Tasks

### Task 1: Add the dependency-only aggregate module

**Files:**

- Modify: `pom.xml`
- Create: `dedup4j-spring-boot-observability/pom.xml`

- [ ] **Step 1: Register the reactor module**

Add immediately after `dedup4j-spring-boot-dashboard`:

```xml
<module>dedup4j-spring-boot-observability</module>
```

- [ ] **Step 2: Create the aggregate JAR POM**

Use standard `jar` packaging and these direct compile dependencies:

```xml
<dependencies>
    <dependency>
        <groupId>com.edem</groupId>
        <artifactId>dedup4j-spring-boot-management</artifactId>
        <version>${project.version}</version>
    </dependency>
    <dependency>
        <groupId>com.edem</groupId>
        <artifactId>dedup4j-spring-boot-dashboard</artifactId>
        <version>${project.version}</version>
    </dependency>
    <dependency>
        <groupId>org.junit.jupiter</groupId>
        <artifactId>junit-jupiter</artifactId>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-test</artifactId>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>org.springframework</groupId>
        <artifactId>spring-test</artifactId>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>jakarta.servlet</groupId>
        <artifactId>jakarta.servlet-api</artifactId>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>org.assertj</groupId>
        <artifactId>assertj-core</artifactId>
        <scope>test</scope>
    </dependency>
</dependencies>
```

Set the artifact description to `Optional aggregate starter for dedup4j management and embedded dashboard.` Do not add production Java source or an `AutoConfiguration.imports` file; the dependency modules already publish theirs.

- [ ] **Step 3: Confirm the new module builds as an ordinary JAR**

```bash
./mvnw -pl dedup4j-spring-boot-observability -am package -DskipTests
```

Expected: Maven produces `dedup4j-spring-boot-observability-0.0.1-SNAPSHOT.jar` and resolves both component modules transitively.

### Task 2: Prove the aggregate classpath boundary

**Files:**

- Create: `dedup4j-spring-boot-observability/src/test/java/com/edem/dedup4j/observability/ObservabilityStarterClasspathTest.java`
- Modify: `dedup4j-spring-boot-starter/src/test/java/com/edem/dedup4j/autoconfigure/GenericStarterDependencyTest.java`

- [ ] **Step 1: Test included classes and resources**

```java
@Test
void includesManagementAndEmbeddedDashboard() {
    assertNotNull(Dedup4jManagementAutoConfiguration.class);
    assertNotNull(Dedup4jDashboardAutoConfiguration.class);
    assertNotNull(getClass().getResource(
            "/static/dedup4j/dashboard/index.html"
    ));
}
```

- [ ] **Step 2: Test that fleet code is absent**

Use `Class.forName` and assert `ClassNotFoundException` for:

```text
com.edem.dedup4j.dashboard.Dedup4jDashboardApplication
com.edem.dedup4j.dashboard.persistence.MetricSnapshotRepository
```

Do not add `dedup4j-dashboard` as a test dependency.

- [ ] **Step 3: Strengthen the upload-starter exclusion test**

Add `ClassNotFoundException` assertions for the management auto-configuration, dashboard auto-configuration, and one unique standalone-dashboard class to `GenericStarterDependencyTest`. Because any aggregate dependency would bring both component modules transitively, absence of those component classes proves the aggregate is not in the starter graph. During verification, also run:

```bash
./mvnw -pl dedup4j-spring-boot-starter dependency:tree \
  -Dincludes=com.edem:dedup4j-spring-boot-management,com.edem:dedup4j-spring-boot-dashboard,com.edem:dedup4j-spring-boot-observability,com.edem:dedup4j-dashboard
```

Expected: none of these artifact IDs appears in the starter dependency tree:

```text
dedup4j-spring-boot-management
dedup4j-spring-boot-dashboard
dedup4j-spring-boot-observability
dedup4j-dashboard
```

- [ ] **Step 4: Run both boundary tests**

```bash
./mvnw -pl dedup4j-spring-boot-starter,dedup4j-spring-boot-observability -am test -Dtest=GenericStarterDependencyTest,ObservabilityStarterClasspathTest
```

Expected: aggregate includes single-instance observability, upload starter includes none, and fleet code remains absent from both.

### Task 3: Preserve activation and safety semantics

**Files:**

- Create: `dedup4j-spring-boot-observability/src/test/java/com/edem/dedup4j/observability/ObservabilityStarterContextTest.java`

- [ ] **Step 1: Create a servlet application context runner**

Use `WebApplicationContextRunner` with:

```java
AutoConfigurations.of(
        Dedup4jManagementAutoConfiguration.class,
        Dedup4jDashboardAutoConfiguration.class
)
```

Supply `Dedup4jProperties` as the only user bean required by the embedded dashboard snapshot service.

- [ ] **Step 2: Test the default aggregate state**

Without activation properties, assert:

```java
assertThat(context).hasSingleBean(EmbeddedDashboardController.class);
assertThat(context).doesNotHaveBean(Dedup4jManagementController.class);
```

This deliberately preserves the existing dashboard default-on and management default-off behavior.

- [ ] **Step 3: Test explicit enablement and disablement**

With `dedup4j.management.enabled=true`, assert both controllers exist once. Inspect their mappings from `RequestMappingHandlerMapping` and assert every declared HTTP method is `GET` or `HEAD`; fail if a mapping allows all methods or includes `POST`, `PUT`, `PATCH`, or `DELETE`. With:

```text
dedup4j.dashboard.enabled=false
dedup4j.management.enabled=false
```

assert neither exists. Also assert no endpoint bean is created in a non-servlet `ApplicationContextRunner` for the dashboard configuration.

- [ ] **Step 4: Run aggregate context tests**

```bash
./mvnw -pl dedup4j-spring-boot-observability test -Dtest=ObservabilityStarterContextTest
```

### Task 4: Document the two observability deployment choices

**Files:**

- Modify: `README.md`
- Modify: `docs/architecture.md`
- Modify: `docs/implementation.md`
- Modify: `docs/changelog.md`

- [ ] **Step 1: Document one-dependency embedded setup**

Add:

```xml
<dependency>
    <groupId>com.edem</groupId>
    <artifactId>dedup4j-spring-boot-observability</artifactId>
    <version>${dedup4j.version}</version>
</dependency>
```

State that this installs the embedded single-application dashboard and makes the management module available. Show `dedup4j.management.enabled=true` as a separate explicit choice; do not imply installation exposes it automatically.

- [ ] **Step 2: Keep the standalone distinction explicit**

Document:

```text
dedup4j-spring-boot-observability = embedded current-application UI/API
dedup4j-dashboard = separate multi-instance polling/history application
```

The aggregate must not be described as a fleet dashboard.

- [ ] **Step 3: Update living documentation after implementation**

Add the module to `docs/architecture.md`, its no-source aggregation behavior and tests to `docs/implementation.md`, and a dated entry to `docs/changelog.md`. Preserve unrelated sections.

### Task 5: Verify the whole packaging graph

**Files:** none beyond the tasks above.

- [ ] **Step 1: Run focused observability verification**

```bash
./mvnw --batch-mode --no-transfer-progress -pl dedup4j-spring-boot-management,dedup4j-spring-boot-dashboard,dedup4j-spring-boot-observability -am verify
```

- [ ] **Step 2: Run full reactor verification**

```bash
./mvnw --batch-mode --no-transfer-progress verify
```

Expected: `BUILD SUCCESS`; upload-starter boundary tests and all existing embedded/standalone dashboard tests pass.

- [ ] **Step 3: Report changed files to Edem**

Do not run Git commands. Provide the path list and test results for Edem's Git workflow.

## Definition of Done

- [ ] One optional dependency supplies both existing single-application observability modules.
- [ ] The generic upload starter has no observability artifacts.
- [ ] Management remains disabled until explicitly enabled.
- [ ] The embedded dashboard remains independently disableable.
- [ ] Standalone multi-instance monitoring remains separate.
- [ ] Component modules remain independently usable and tested.
- [ ] Full reactor verification passes.
- [ ] No Git operation was performed without explicit permission.
