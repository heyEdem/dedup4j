package com.edem.dedup4j.dashboard.registration;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record InstanceRegistration(
        UUID instanceId,
        String instanceName,
        String advertisedUrl,
        Instant registeredAt
) {
    public InstanceRegistration {
        Objects.requireNonNull(instanceId, "instanceId must not be null");
        requireText(instanceName, "instanceName");
        requireText(advertisedUrl, "advertisedUrl");
        Objects.requireNonNull(registeredAt, "registeredAt must not be null");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
