package com.edem.blobhelper.facade;

import java.util.List;
import java.util.Objects;

public record BatchStoreResult(List<BatchStoreOutcome> outcomes) {

    public BatchStoreResult {
        Objects.requireNonNull(outcomes, "outcomes must not be null");
        outcomes = List.copyOf(outcomes);
    }

    public List<BlobStoreSuccess> successes() {
        return outcomes.stream()
                .filter(BlobStoreSuccess.class::isInstance)
                .map(BlobStoreSuccess.class::cast)
                .toList();
    }

    public List<BlobStoreFailure> failures() {
        return outcomes.stream()
                .filter(BlobStoreFailure.class::isInstance)
                .map(BlobStoreFailure.class::cast)
                .toList();
    }

    public boolean allSucceeded() {
        return failures().isEmpty();
    }
}
