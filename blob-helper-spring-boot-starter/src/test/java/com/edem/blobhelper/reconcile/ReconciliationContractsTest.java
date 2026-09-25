package com.edem.blobhelper.reconcile;

import com.edem.dedup4j.core.exception.BlobValidationException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReconciliationContractsTest {

    @Test
    void createsMismatchWithExpectedAndActualCounts() {
        UUID contentId = UUID.randomUUID();

        ReconciliationMismatch mismatch = new ReconciliationMismatch(contentId, 4, 2);

        assertThat(mismatch.assetContentId()).isEqualTo(contentId);
        assertThat(mismatch.expectedReferenceCount()).isEqualTo(4);
        assertThat(mismatch.actualReferenceCount()).isEqualTo(2);
    }

    @Test
    void reportDefensivelyCopiesMismatches() {
        List<ReconciliationMismatch> mismatches = new ArrayList<>();
        ReconciliationMismatch mismatch = new ReconciliationMismatch(UUID.randomUUID(), 2, 1);
        mismatches.add(mismatch);

        ReconciliationReport report = new ReconciliationReport(3, mismatches);
        mismatches.clear();

        assertThat(report.checkedContentCount()).isEqualTo(3);
        assertThat(report.mismatches()).containsExactly(mismatch);
        assertThatThrownBy(() -> report.mismatches().add(mismatch))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsInvalidCountsAndIds() {
        UUID contentId = UUID.randomUUID();

        assertThatThrownBy(() -> new ReconciliationMismatch(null, 1, 1))
                .isInstanceOf(BlobValidationException.class);
        assertThatThrownBy(() -> new ReconciliationMismatch(contentId, -1, 1))
                .isInstanceOf(BlobValidationException.class);
        assertThatThrownBy(() -> new ReconciliationMismatch(contentId, 1, -1))
                .isInstanceOf(BlobValidationException.class);
        assertThatThrownBy(() -> new ReconciliationReport(-1, List.of()))
                .isInstanceOf(BlobValidationException.class);
    }

    @Test
    void logicalReferenceSourceReturnsCountsByContentId() {
        UUID contentId = UUID.randomUUID();
        LogicalReferenceCountSource source = () -> Map.of(contentId, 7L);

        assertThat(source.countLogicalReferences()).containsEntry(contentId, 7L);
    }
}
