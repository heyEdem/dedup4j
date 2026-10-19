package com.edem.dedup4j.history;

import java.time.Instant;
import java.util.UUID;

public record FailureRecord(
        long sequenceNo,
        UUID id,
        Instant recordedAt,
        String operation,
        String message,
        String instanceId,
        String contentHash,
        String objectKey) {
}
