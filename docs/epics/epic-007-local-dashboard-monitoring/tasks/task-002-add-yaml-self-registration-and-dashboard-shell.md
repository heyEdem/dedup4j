# Task 7.2: Add YAML Self-Registration and Dashboard Shell

**Status:** Complete
**Source:** [PLAN-007](../../../implementation-plans/PLAN-007-local-dashboard-monitoring.md)
**ADR:** [ADR-005](../../../adrs/ADR-005-local-dashboard-pull-monitoring.md)

## Goal

Allow local Blob Helper applications to register themselves and provide a
standalone dashboard process bound to loopback.

## Files

- Modify: `pom.xml`
- Create: `dedup4j-dashboard/pom.xml`
- Create: `dedup4j-dashboard/src/main/java/com/edem/dedup4j/dashboard/Dedup4jDashboardApplication.java`
- Create: `dedup4j-dashboard/src/main/java/com/edem/dedup4j/dashboard/registration/InstanceRegistrationController.java`
- Create: `dedup4j-dashboard/src/main/java/com/edem/dedup4j/dashboard/registration/InstanceRegistrationClient.java`
- Create: `dedup4j-dashboard/src/main/java/com/edem/dedup4j/dashboard/registration/InstanceRegistration.java`
- Create: `dedup4j-dashboard/src/main/resources/application.yaml`
- Create: `dedup4j-dashboard/src/test/java/com/edem/dedup4j/dashboard/registration/InstanceRegistrationTest.java`

## Acceptance

- [x] Instances opt in through `application.yaml` with dashboard URL, instance
      name, and advertised management URL.
- [x] Registration creates or updates an instance by stable generated ID.
- [x] Duplicate startup registration is idempotent.
- [x] Dashboard defaults to `server.address=127.0.0.1` and port `9090`.
- [x] Dashboard registration accepts local requests without tokens in the MVP.
- [x] An unreachable instance remains registered; polling-based recovery is
      provided by the follow-up polling task without manual re-registration.
