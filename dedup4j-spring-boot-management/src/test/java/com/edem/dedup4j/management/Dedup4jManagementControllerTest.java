package com.edem.dedup4j.management;

import com.edem.dedup4j.history.DefaultInstanceId;
import com.edem.dedup4j.history.DisabledUploadHistory;
import com.edem.dedup4j.history.UploadHistory;
import com.edem.dedup4j.jpa.AssetContentRepository;
import com.edem.dedup4j.observability.Dedup4jMetrics;
import com.edem.dedup4j.reconcile.ReconciliationMismatch;
import com.edem.dedup4j.reconcile.ReconciliationRun;
import com.edem.dedup4j.reconcile.ReconciliationService;
import com.edem.dedup4j.reconcile.ReconciliationSummary;
import com.edem.dedup4j.reconcile.ScheduledReconciliation;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class Dedup4jManagementControllerTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(Dedup4jManagementAutoConfiguration.class));

    @Test
    void managementIsDisabledByDefault() {
        contextRunner.run(context -> assertThat(context).doesNotHaveBean(Dedup4jManagementController.class));
    }

    @Test
    void managementIsCreatedWhenExplicitlyEnabled() {
        contextRunner.withPropertyValues(
                        "dedup4j.management.enabled=true",
                        "dedup4j.storage.provider=local")
                .run(context -> assertThat(context).hasSingleBean(Dedup4jManagementController.class));
    }

    @Test
    void historyFailureSourceIsRegisteredWhenEnabled() {
        contextRunner.withPropertyValues("dedup4j.management.enabled=true", "dedup4j.storage.provider=local")
                .withBean(UploadHistory.class, DisabledUploadHistory::new)
                .run(context -> assertThat(context.getBean(Dedup4jManagementSnapshot.FailureSource.class))
                        .isInstanceOf(UploadHistoryFailureSource.class));
    }

    @Test
    void userFailureSourceWins() {
        Dedup4jManagementSnapshot.FailureSource user = since -> List.<Dedup4jManagementSnapshot.Failure>of();
        contextRunner.withPropertyValues("dedup4j.management.enabled=true", "dedup4j.storage.provider=local")
                .withBean(UploadHistory.class, DisabledUploadHistory::new)
                .withBean(Dedup4jManagementSnapshot.FailureSource.class, () -> user)
                .run(context -> {
                    assertThat(context).doesNotHaveBean(UploadHistoryFailureSource.class);
                    assertThat(context.getBean(Dedup4jManagementSnapshot.FailureSource.class)).isSameAs(user);
                });
    }

    @Test
    void defaultInstanceIdIsSharedWithHistory() {
        assertThat(new Dedup4jManagementProperties().getInstanceId()).isEqualTo(DefaultInstanceId.value());
    }

    @Test
    void failureKeepsItsOriginalFourArgumentConstructor() {
        UUID id = UUID.randomUUID();
        Instant at = Instant.parse("2026-10-19T00:00:00Z");

        var failure = new Dedup4jManagementSnapshot.Failure(id, at, "upload", "boom");

        assertThat(failure).isEqualTo(new Dedup4jManagementSnapshot.Failure(id, at, "upload", "boom", null, null, null, 0));
    }

    @Test
    void metricsResponseContainsProviderNeutralCounters() {
        Dedup4jManagementSnapshot.Metrics metrics = new Dedup4jManagementSnapshot.Metrics("ds", "all-time", 4, 2, 2, 2, 100, 50, 3, 75);
        assertThat(metrics.uploads()).isEqualTo(4);
        assertThat(metrics.acceptedBytes()).isEqualTo(100);
        assertThat(metrics.avoidedBytes()).isEqualTo(50);
        assertThat(Arrays.stream(metrics.getClass().getRecordComponents()).map(component -> component.getType().getName()))
                .allMatch(type -> !type.contains("software.amazon") && !type.contains("com.azure"));
    }

    @Test
    void managementEndpointsAreReadOnly() {
        List<RequestMethod> methods = Arrays.stream(Dedup4jManagementController.class.getDeclaredMethods())
                .map(method -> AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class))
                .filter(Objects::nonNull)
                .flatMap(mapping -> Arrays.stream(mapping.method()))
                .toList();

        assertThat(methods).hasSize(5).containsOnly(RequestMethod.GET);
    }

    @Test
    void reconciliationIsReportedDisabledWithoutASchedule() {
        contextRunner.withPropertyValues("dedup4j.management.enabled=true", "dedup4j.storage.provider=local",
                        "dedup4j.management.instance-id=orders-1")
                .run(context -> assertThat(context.getBean(Dedup4jManagementController.class).reconciliation())
                        .isEqualTo(new Dedup4jManagementSnapshot.Reconciliation(false, "orders-1", null, null)));
    }

    @Test
    void reconciliationExposesThisInstancesLastRun() {
        ScheduledReconciliation scheduled = new ScheduledReconciliation(
                new ReconciliationService(new AssetContentRepository(unusedEntityManager())),
                () -> {
                    throw new IllegalStateException("source down");
                },
                new CronTrigger("0 0 3 * * *", ZoneOffset.UTC), 10, 10, new Dedup4jMetrics(null), Clock.systemUTC());
        contextRunner.withPropertyValues("dedup4j.management.enabled=true", "dedup4j.storage.provider=local",
                        "dedup4j.management.instance-id=orders-1")
                .withBean(ScheduledReconciliation.class, () -> scheduled)
                .run(context -> {
                    ReconciliationRun run = scheduled.runNow();

                    Dedup4jManagementSnapshot.Reconciliation view =
                            context.getBean(Dedup4jManagementController.class).reconciliation();

                    assertThat(view.enabled()).isTrue();
                    assertThat(view.instanceId()).isEqualTo("orders-1");
                    assertThat(view.cron()).isEqualTo("0 0 3 * * *");
                    assertThat(view.lastRun()).isEqualTo(run);
                    assertThat(view.lastRun().failureMessage()).isEqualTo("source down");
                });
    }

    @Test
    void reconciliationSerializesSucceededAndAbsentRuns() {
        ReconciliationRun run = new ReconciliationRun(
                Instant.parse("2026-10-26T03:00:00Z"), Instant.parse("2026-10-26T03:00:01Z"),
                ReconciliationRun.Outcome.SUCCEEDED,
                new ReconciliationSummary(5, 2, 1,
                        List.of(new ReconciliationMismatch(UUID.fromString("00000000-0000-0000-0000-000000000001"), 3, 1)),
                        List.of(UUID.fromString("00000000-0000-0000-0000-000000000002"))),
                null, null);
        JsonMapper json = JsonMapper.builder().build();

        String withRun = json.writeValueAsString(
                new Dedup4jManagementSnapshot.Reconciliation(true, "orders-1", "0 0 3 * * *", run));
        String disabled = json.writeValueAsString(
                new Dedup4jManagementSnapshot.Reconciliation(false, "orders-1", null, null));

        assertThat(withRun)
                .contains("\"outcome\":\"SUCCEEDED\"")
                .contains("\"mismatchCount\":2")
                .contains("\"danglingReferenceSample\":[\"00000000-0000-0000-0000-000000000002\"]")
                .contains("\"expectedReferenceCount\":3")
                .contains("\"startedAt\":\"2026-10-26T03:00:00Z\"");
        assertThat(disabled).contains("\"enabled\":false").contains("\"lastRun\":null");
    }

    private static EntityManager unusedEntityManager() {
        return (EntityManager) Proxy.newProxyInstance(EntityManager.class.getClassLoader(),
                new Class<?>[]{EntityManager.class}, (proxy, method, args) -> {
                    throw new UnsupportedOperationException(method.getName());
                });
    }
}
