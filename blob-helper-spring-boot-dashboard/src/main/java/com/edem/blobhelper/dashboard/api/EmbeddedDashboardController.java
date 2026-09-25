package com.edem.blobhelper.dashboard.api;

import com.edem.blobhelper.dashboard.autoconfigure.BlobHelperDashboardProperties;
import com.edem.dedup4j.management.Dedup4jManagementSnapshot;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("${blob-helper.dashboard.base-path:/blob-helper/dashboard}/api/v1")
public class EmbeddedDashboardController {
    private final EmbeddedDashboardSnapshotService snapshots;
    private final BlobHelperDashboardProperties properties;
    private final Dedup4jManagementSnapshot.FailureSource failureSource;

    public EmbeddedDashboardController(EmbeddedDashboardSnapshotService snapshots,
                                        BlobHelperDashboardProperties properties,
                                        Dedup4jManagementSnapshot.FailureSource failureSource) {
        this.snapshots = snapshots;
        this.properties = properties;
        this.failureSource = failureSource;
    }

    @GetMapping("/overview")
    public EmbeddedDashboardView.Overview overview() {
        var snapshot = snapshots.current();
        return new EmbeddedDashboardView.Overview(1, 1, snapshot.uploads(), snapshot.duplicates(),
                snapshot.physicalUploads(), snapshot.logicalBytes(), snapshot.physicalBytes(), snapshot.avoidedBytes(),
                snapshot.duplicateRate(), snapshot.newUploads(), snapshot.contentCount(), List.of());
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

    private UUID instanceId(EmbeddedDashboardSnapshotService.Snapshot snapshot) {
        try { return UUID.fromString(snapshot.instanceId()); }
        catch (RuntimeException ignored) { return UUID.nameUUIDFromBytes(snapshot.instanceId().getBytes()); }
    }

    public static WebMvcConfigurer resourceConfiguration(BlobHelperDashboardProperties properties) {
        return new WebMvcConfigurer() {
            @Override
            public void addResourceHandlers(ResourceHandlerRegistry registry) {
                registry.addResourceHandler(properties.getBasePath() + "/**")
                        .addResourceLocations("classpath:/static/blob-helper/dashboard/");
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
        return trailingSlash ? "forward:/blob-helper/dashboard/index.html" : "redirect:" + basePath + "/";
    }
}
