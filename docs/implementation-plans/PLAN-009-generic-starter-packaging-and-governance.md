# Generic Starter Packaging and Governance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `blob-helper-spring-boot-starter` the single standard upload dependency while enforcing provider-module ownership, dependency convergence, and vulnerability-review safeguards.

**Architecture:** The starter depends transitively on the existing local, S3, and Azure adapters, but provider SDK coordinates remain declared only in the corresponding provider POMs. Maven and GitHub checks guard the larger transitive graph. Dashboard and management artifacts remain excluded.

**Tech Stack:** Maven, Java 21, JUnit 5, Maven Enforcer, GitHub Dependabot, GitHub dependency-review action.

**Implements:** ADR-007

---

## Five-Questions Contract

- **Protected outcome (Q1):** one standard starter dependency supplies every supported storage adapter.
- **Invariants (Q2):** provider implementation isolation, no observability dependencies, converged shared libraries, credential-free verification.
- **Owner (Q3):** starter/root POMs, provider boundary tests, and GitHub supply-chain automation.
- **Proof (Q4):** the acceptance tests below.
- **Exclusions (Q5):** no SDK declarations in core/starter; no dashboard/management dependency; no external credentials; no Git operations without Edem's explicit instruction.

## File Map

- Modify: `blob-helper-spring-boot-starter/pom.xml` — make provider adapters compile dependencies.
- Modify: `pom.xml` — add Enforcer version and dependency-convergence execution.
- Create: `blob-helper-spring-boot-starter/src/test/java/com/edem/blobhelper/autoconfigure/GenericStarterDependencyTest.java` — prove one starter classpath contains all adapters and excludes observability.
- Modify: `dedup4j-core/src/test/java/com/edem/dedup4j/core/ProviderDependencyBoundaryTest.java` — clarify that transitive adapter inclusion is allowed while direct SDK ownership remains isolated.
- Create: `.github/dependabot.yml` — weekly Maven and Actions update proposals.
- Create: `.github/workflows/dependency-review.yml` — reject newly introduced high/critical vulnerable dependencies.
- Modify after implementation: `docs/architecture.md`, `docs/implementation.md`, `docs/changelog.md`, `README.md`.

## Acceptance Criteria (from Q4)

- [ ] **GenericStarterDependencyTest.includesAllProviderAdapters:** starter tests can load `LocalBlobStorage`, `S3BlobStorage`, and `AzureBlobStorage` without test-scoped provider dependencies.
- [ ] **GenericStarterDependencyTest.excludesObservabilityModules:** the starter dependency list does not contain management, embedded dashboard, or standalone dashboard artifacts.
- [ ] **ProviderDependencyBoundaryTest.providerSdksStayInProviderModules:** AWS/Azure coordinates are declared only by their adapter POMs.
- [ ] **dependencyConvergence:** the Maven Enforcer convergence rule passes for Netty, Jackson, Reactor, HTTP components, and SLF4J.
- [ ] **dependency-review:** pull requests introducing high/critical vulnerable dependencies fail the dependency-review workflow.

## Out of Scope (from Q5)

- Provider implementation classes — this plan changes packaging, not storage behavior.
- `dedup4j-core` production dependencies — core remains provider/framework neutral.
- `blob-helper-spring-boot-management`, `blob-helper-spring-boot-dashboard`, `blob-helper-dashboard` — none become starter dependencies.
- SDK version upgrades unrelated to achieving convergence.
- Git commits, pushes, branches, or pull requests — Edem handles Git unless explicitly delegating it.

## Tasks

### Task 1: Prove the desired starter classpath

**Files:**

- Create: `blob-helper-spring-boot-starter/src/test/java/com/edem/blobhelper/autoconfigure/GenericStarterDependencyTest.java`

- [ ] **Step 1: Write the failing adapter-availability test**

```java
package com.edem.blobhelper.autoconfigure;

import com.edem.blobhelper.storage.azure.AzureBlobStorage;
import com.edem.dedup4j.storage.local.LocalBlobStorage;
import com.edem.blobhelper.storage.s3.S3BlobStorage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GenericStarterDependencyTest {

    @Test
    void includesAllProviderAdapters() {
        assertNotNull(LocalBlobStorage.class);
        assertNotNull(S3BlobStorage.class);
        assertNotNull(AzureBlobStorage.class);
    }

    @Test
    void excludesObservabilityModules() {
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("com.edem.blobhelper.management.BlobHelperManagementAutoConfiguration"));
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("com.edem.blobhelper.dashboard.autoconfigure.BlobHelperDashboardAutoConfiguration"));
    }
}
```

- [ ] **Step 2: Run the focused test and confirm the missing compile dependencies**

Run:

```bash
./mvnw -pl blob-helper-spring-boot-starter test -Dtest=GenericStarterDependencyTest
```

Expected: test compilation fails because the S3 and Azure adapter packages are absent from the starter compile classpath.

### Task 2: Aggregate provider adapters through the starter

**Files:**

- Modify: `blob-helper-spring-boot-starter/pom.xml`

- [ ] **Step 1: Replace the test-only local dependency with three compile dependencies**

```xml
<dependency>
    <groupId>com.edem</groupId>
    <artifactId>dedup4j-storage-local</artifactId>
    <version>${project.version}</version>
</dependency>
<dependency>
    <groupId>com.edem</groupId>
    <artifactId>blob-helper-storage-s3</artifactId>
    <version>${project.version}</version>
</dependency>
<dependency>
    <groupId>com.edem</groupId>
    <artifactId>blob-helper-storage-azure</artifactId>
    <version>${project.version}</version>
</dependency>
```

Do not declare `software.amazon.awssdk:*` or `com.azure:*` directly in the starter.

- [ ] **Step 2: Run the classpath and ownership tests**

Run:

```bash
./mvnw -pl dedup4j-core,blob-helper-spring-boot-starter -am test -Dtest=ProviderDependencyBoundaryTest,GenericStarterDependencyTest
```

Expected: both tests pass; provider adapters are visible and SDK ownership remains provider-local.

### Task 3: Enforce convergence for shared SDK infrastructure

**Files:**

- Modify: `pom.xml`

- [ ] **Step 1: Add the Enforcer version property**

```xml
<maven-enforcer.version>3.6.3</maven-enforcer.version>
```

- [ ] **Step 2: Add the convergence execution under root `build/plugins`**

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-enforcer-plugin</artifactId>
    <version>${maven-enforcer.version}</version>
    <executions>
        <execution>
            <id>provider-dependency-convergence</id>
            <phase>validate</phase>
            <goals><goal>enforce</goal></goals>
            <configuration>
                <rules>
                    <dependencyConvergence>
                        <includes>
                            <include>io.netty:*</include>
                            <include>com.fasterxml.jackson.core:*</include>
                            <include>com.fasterxml.jackson.datatype:*</include>
                            <include>io.projectreactor:*</include>
                            <include>io.projectreactor.netty:*</include>
                            <include>org.apache.httpcomponents.client5:*</include>
                            <include>org.apache.httpcomponents.core5:*</include>
                            <include>org.slf4j:*</include>
                        </includes>
                    </dependencyConvergence>
                </rules>
            </configuration>
        </execution>
    </executions>
</plugin>
```

- [ ] **Step 3: Run validation and resolve only demonstrated convergence failures**

Run:

```bash
./mvnw --batch-mode --no-transfer-progress validate
```

Expected: `BUILD SUCCESS`. If the rule identifies a mismatch, align it through the root Spring Boot BOM or the official provider BOM; do not add arbitrary exclusions merely to silence the rule.

### Task 4: Add automated dependency security review

**Files:**

- Create: `.github/dependabot.yml`
- Create: `.github/workflows/dependency-review.yml`

- [ ] **Step 1: Add weekly Maven and Actions update checks**

```yaml
version: 2
updates:
  - package-ecosystem: maven
    directory: /
    schedule:
      interval: weekly
    open-pull-requests-limit: 5
  - package-ecosystem: github-actions
    directory: /
    schedule:
      interval: weekly
```

- [ ] **Step 2: Add the pull-request vulnerability gate**

```yaml
name: Dependency Review

on:
  pull_request:
    branches: [main, staging, dev]

permissions:
  contents: read

jobs:
  dependency-review:
    runs-on: ubuntu-latest
    permissions:
      contents: read
    steps:
      - uses: actions/checkout@v4
      - uses: actions/dependency-review-action@v4
        with:
          fail-on-severity: high
```

- [ ] **Step 3: Validate YAML syntax locally if `actionlint` is installed**

Run:

```bash
command -v actionlint >/dev/null && actionlint .github/workflows/dependency-review.yml || true
```

Expected: no YAML/workflow errors when `actionlint` is available.

### Task 5: Verify and document the packaging contract

**Files:**

- Modify: `README.md`
- Modify: `docs/architecture.md`
- Modify: `docs/implementation.md`
- Modify: `docs/changelog.md`

- [ ] **Step 1: Replace two-dependency examples with the single starter coordinate**

Document that provider modules remain internal architecture and that observability uses a separate optional artifact.

- [ ] **Step 2: Run final verification**

Run:

```bash
./mvnw --batch-mode --no-transfer-progress verify
```

Expected: `BUILD SUCCESS`, including convergence and provider ownership tests.

- [ ] **Step 3: Report the exact changed files to Edem for Git handling**

Do not run Git commands. List the modified paths and verification result in the handoff.

## Definition of Done

- [ ] All acceptance criteria pass.
- [ ] The standard starter has compile dependencies on all three adapters and no observability modules.
- [ ] SDK coordinates remain declared only by provider modules.
- [ ] Convergence and dependency-review safeguards exist.
- [ ] Documentation shows one standard dependency.
- [ ] No Git operation was performed without explicit permission.
