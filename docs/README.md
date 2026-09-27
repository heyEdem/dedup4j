# dedup4j Documentation

## Documents

- [Architecture Index](architecture.md): current codebase map and module overview.
- [Implementation Index](implementation.md): entry points, module breakdown, and configuration.
- [Patterns Index](patterns.md): observed naming, folder, and testing conventions.
- [Decision Index](decisions.md): summarized architectural decisions.
- [Changelog](changelog.md): index maintenance log.
- [PR Message Style](pr-message-style.md): Edem's preferred pull request writeup structure.
- [Specification](SPECIFICATION.md): project requirements, architecture, data model, storage adapter design, flows, and implementation phases.
- [Task Index](taskindex.md): epic/task breakdown generated from ADRs and implementation plans.
- [ADRs](adrs/): architecture decisions.
- [Implementation Plans](implementation-plans/): phase-level implementation plans.
- [Epics](epics/): per-epic implementation task folders.
- [Local dashboard design](plans/2026-08-28-local-dashboard-design.md): approved
  MVP design for multi-instance local monitoring.
- Local dashboard verification is covered by credential-free integration tests
  using in-process management endpoints and temporary SQLite storage.

## Project Goal

Build a reusable Spring Boot starter for deduplicated blob uploads, with pluggable object storage support for S3, Azure Blob Storage, local storage, and future providers.
