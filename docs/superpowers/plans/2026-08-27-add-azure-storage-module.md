# Add Azure Storage Module Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an isolated `dedup4j-storage-azure` Maven module with Azure Blob SDK dependency management and configurable Azure connection properties.

**Architecture:** Add the provider module to the root reactor, import the Azure SDK BOM inside that module only, and expose a plain properties bean that contains container, connection string, endpoint, and account name settings. No core or starter API changes are needed; the Azure adapter implementation remains task 5.4.

**Tech Stack:** Java 21, Maven, JUnit Jupiter, Azure SDK for Java (`com.azure:azure-sdk-bom` and `com.azure:azure-storage-blob`).

---

### Task 1: Add Azure properties test and module structure

**Files:**
- Modify: `pom.xml`
- Create: `dedup4j-storage-azure/pom.xml`
- Create: `dedup4j-storage-azure/src/main/java/com/edem/dedup4j/storage/azure/AzureBlobStorageProperties.java`
- Test: `dedup4j-storage-azure/src/test/java/com/edem/dedup4j/storage/azure/AzureBlobStoragePropertiesTest.java`

- [x] **Step 1: Write the failing test**

Create a package-private JUnit test that assigns and reads the four provider settings:

```java
@Test
void storesAzureConnectionSettings() {
    AzureBlobStorageProperties properties = new AzureBlobStorageProperties();
    URI endpoint = URI.create("https://account.blob.core.windows.net");

    properties.setContainer("blob-container");
    properties.setConnectionString("UseDevelopmentStorage=true");
    properties.setEndpoint(endpoint);
    properties.setAccountName("account");

    assertEquals("blob-container", properties.getContainer());
    assertEquals("UseDevelopmentStorage=true", properties.getConnectionString());
    assertEquals(endpoint, properties.getEndpoint());
    assertEquals("account", properties.getAccountName());
}
```

- [x] **Step 2: Run the test and verify it fails because the module and properties type do not exist**

Run: `./mvnw -pl dedup4j-storage-azure test`

Expected: Maven reports that the requested project/module is not present in the reactor.

- [x] **Step 3: Add the reactor entry, isolated Azure BOM/dependency, properties bean, and test**

Add `<module>dedup4j-storage-azure</module>` to the root `<modules>` list. The new module imports `com.azure:azure-sdk-bom` version `1.3.8`, declares `com.azure:azure-storage-blob` without a version, depends on `dedup4j-core`, and adds JUnit Jupiter in test scope.

Implement `AzureBlobStorageProperties` with nullable `String container`, `String connectionString`, `URI endpoint`, and `String accountName` fields plus conventional getters and setters. Keep the class free of Azure SDK imports so it can be used as provider configuration without changing core APIs.

- [x] **Step 4: Run the focused module test and verify it passes**

Run: `./mvnw -pl dedup4j-storage-azure test`

Expected: `AzureBlobStoragePropertiesTest` passes and Maven resolves Azure SDK artifacts only for the new module.

- [x] **Step 5: Verify dependency isolation and the full reactor**

Run: `./mvnw -pl dedup4j-storage-azure dependency:tree -Dincludes=com.azure` and `./mvnw verify`.

Expected: the dependency tree contains Azure artifacts for `dedup4j-storage-azure`; the full reactor exits with code 0.

- [x] **Step 6: Update project indexes and task status**

Update `docs/architecture.md`, `docs/implementation.md`, `docs/taskindex.md`, `docs/epics/epic-005-s3-azure-storage-adapters/README.md`, the task file for 5.3, and prepend a dated entry to `docs/changelog.md`. Do not add an ADR because this follows the existing provider-isolation decision in ADR-004.

- [x] **Step 7: Review the changed-file diff and commit the completed task**

Run `git diff HEAD~1 --name-only`, re-scan the changed files and their direct neighbors, then commit the implementation and documentation changes with `git commit -m "feat(storage-azure): add Azure storage module"`.
