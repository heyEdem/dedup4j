package com.edem.dedup4j.management;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class InstanceRegistrationClient {

    private static final Logger log = LoggerFactory.getLogger(InstanceRegistrationClient.class);
    private final DashboardRegistrationProperties properties;
    private final HttpClient httpClient;

    public InstanceRegistrationClient(DashboardRegistrationProperties properties) {
        this(properties, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
    }

    InstanceRegistrationClient(DashboardRegistrationProperties properties, HttpClient httpClient) {
        this.properties = properties;
        this.httpClient = httpClient;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void registerAfterStartup() {
        registerAsync();
    }

    public CompletableFuture<Void> registerAsync() {
        if (!properties.isEnabled() || blank(properties.getDashboardUrl()) || blank(properties.getAdvertisedUrl())) {
            return CompletableFuture.completedFuture(null);
        }

        try {
            HttpRequest request = HttpRequest.newBuilder(registrationUri())
                    .timeout(Duration.ofSeconds(2))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload()))
                    .build();
            return httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                    .thenAccept(response -> {
                        if (response.statusCode() >= 300) {
                            log.warn("dedup4j dashboard registration returned status {}", response.statusCode());
                        }
                    })
                    .exceptionally(failure -> {
                        log.warn("dedup4j dashboard registration unavailable: {}", failure.getMessage());
                        return null;
                    });
        } catch (RuntimeException failure) {
            log.warn("dedup4j dashboard registration could not be started: {}", failure.getMessage());
            return CompletableFuture.completedFuture(null);
        }
    }

    public UUID stableInstanceId() {
        if (!blank(properties.getInstanceId())) {
            return UUID.fromString(properties.getInstanceId());
        }
        String identity = properties.getInstanceName() + "\n" + properties.getAdvertisedUrl();
        return UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8));
    }

    private URI registrationUri() {
        String base = properties.getDashboardUrl().replaceAll("/$", "");
        return URI.create(base + "/api/v1/instances");
    }

    private String payload() {
        return "{\"instanceId\":\"" + stableInstanceId()
                + "\",\"instanceName\":\"" + json(properties.getInstanceName())
                + "\",\"advertisedUrl\":\"" + json(properties.getAdvertisedUrl())
                + "\",\"registeredAt\":\"" + Instant.now() + "\"}";
    }

    private static String json(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
