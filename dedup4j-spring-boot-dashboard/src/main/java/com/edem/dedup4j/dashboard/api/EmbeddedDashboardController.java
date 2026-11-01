package com.edem.dedup4j.dashboard.api;

import com.edem.dedup4j.dashboard.autoconfigure.Dedup4jDashboardProperties;
import com.edem.dedup4j.jpa.AssetContentRepository;
import com.edem.dedup4j.management.ContentCatalog;
import com.edem.dedup4j.management.Dedup4jManagementSnapshot;
import com.edem.dedup4j.reconcile.ContentReferenceCountSource;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("${dedup4j.dashboard.base-path:/dedup4j/dashboard}/api/v1")
public class EmbeddedDashboardController {
    private final EmbeddedDashboardSnapshotService snapshots;
    private final Dedup4jDashboardProperties properties;
    private final Dedup4jManagementSnapshot.FailureSource failureSource;
    private final ContentCatalog contents;

    public EmbeddedDashboardController(EmbeddedDashboardSnapshotService snapshots,
                                        Dedup4jDashboardProperties properties,
                                        Dedup4jManagementSnapshot.FailureSource failureSource) {
        this(snapshots, properties, failureSource, new ContentCatalog(
                new StaticListableBeanFactory().getBeanProvider(AssetContentRepository.class),
                new StaticListableBeanFactory().getBeanProvider(ContentReferenceCountSource.class)));
    }

    public EmbeddedDashboardController(EmbeddedDashboardSnapshotService snapshots,
                                        Dedup4jDashboardProperties properties,
                                        Dedup4jManagementSnapshot.FailureSource failureSource,
                                        ContentCatalog contents) {
        this.contents = contents;
        this.snapshots = snapshots;
        this.properties = properties;
        this.failureSource = failureSource;
    }

    @GetMapping("/overview")
    public EmbeddedDashboardView.Overview overview() {
        var snapshot = snapshots.current();
        return new EmbeddedDashboardView.Overview(1, 1, snapshot.uploads(), snapshot.duplicates(),
                snapshot.physicalWrites(), snapshot.logicalBytes(), snapshot.physicalBytes(), snapshot.avoidedBytes(),
                snapshot.duplicateRate(), snapshot.newUploads(), snapshot.contentCount(), List.of(), snapshot.totalsScope());
    }

    @GetMapping("/instances/status")
    public List<EmbeddedDashboardView.Instance> instanceStatus() {
        var snapshot = snapshots.current();
        Instant now = Instant.now();
        return List.of(new EmbeddedDashboardView.Instance(instanceId(snapshot), snapshot.instanceName(), "", "HEALTHY",
                now, now, null, null, snapshot.uploads(), snapshot.newUploads(), snapshot.duplicates(),
                snapshot.duplicateRate(), snapshot.contentCount(), snapshot.physicalBytes(), snapshot.avoidedBytes()));
    }

    @GetMapping("/instances/{id}/history")
    public EmbeddedDashboardView.History history(@PathVariable UUID id) {
        return new EmbeddedDashboardView.History(id, List.of());
    }

    @GetMapping("/failures")
    public List<Dedup4jManagementSnapshot.Failure> failures(
            @RequestParam(name = "since", required = false) Instant since) {
        Instant boundary = since == null ? Instant.now().minus(properties.getFailureLookback()) : since;
        return failureSource.recentFailures(boundary);
    }

    @GetMapping("/contents")
    public Dedup4jManagementSnapshot.ContentPage contents(
            @RequestParam(name = "sort", required = false) String sort,
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "size", required = false) Integer size) {
        return contents.page(sort, page, size);
    }

    @GetMapping("/contents/{id}")
    public Dedup4jManagementSnapshot.ContentDetail content(@PathVariable("id") UUID id) {
        return contents.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private UUID instanceId(EmbeddedDashboardSnapshotService.Snapshot snapshot) {
        try { return UUID.fromString(snapshot.instanceId()); }
        catch (RuntimeException ignored) { return UUID.nameUUIDFromBytes(snapshot.instanceId().getBytes()); }
    }

    public static WebMvcConfigurer resourceConfiguration(Dedup4jDashboardProperties properties) {
        return new WebMvcConfigurer() {
            @Override
            public void addResourceHandlers(ResourceHandlerRegistry registry) {
                registry.addResourceHandler(properties.getBasePath() + "/**")
                        .addResourceLocations("classpath:/static/dedup4j/dashboard/");
            }
            @Override
            public void addViewControllers(ViewControllerRegistry registry) {
                registry.addViewController(properties.getBasePath())
                        .setViewName(rootViewName(properties.getBasePath(), false));
                registry.addViewController(properties.getBasePath() + "/")
                        .setViewName(rootViewName(properties.getBasePath(), true));
            }
        };
    }

    public static String rootViewName(String basePath, boolean trailingSlash) {
        return trailingSlash ? "forward:/dedup4j/dashboard/index.html" : "redirect:" + basePath + "/";
    }
}
