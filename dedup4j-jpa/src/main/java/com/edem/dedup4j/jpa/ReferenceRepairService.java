package com.edem.dedup4j.jpa;

import com.edem.dedup4j.core.exception.BlobValidationException;
import com.edem.dedup4j.core.exception.ContentNotFoundException;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.function.ToLongFunction;

/**
 * Repairs one content row's reference count under its row lock. The caller owns the transaction,
 * which must run at READ COMMITTED: the application's count is read after the lock is held, so it
 * sees every reference write that committed while repair waited. Writers that change the count and
 * their own reference in one transaction, holding this lock, are therefore never seen half done.
 */
public final class ReferenceRepairService {

    private final AssetContentRepository contents;
    private final ReferenceRepairRepository repairs;

    public ReferenceRepairService(AssetContentRepository contents, ReferenceRepairRepository repairs) {
        this.contents = Objects.requireNonNull(contents, "contents must not be null");
        this.repairs = Objects.requireNonNull(repairs, "repairs must not be null");
    }

    /**
     * Locks the row, then asks {@code logicalCount} for the application's count. A count at zero is
     * left there: its object may already be deleted, and raising the count cannot bring it back. A
     * changed count is recorded in the repair audit in this transaction; a failed record rolls the
     * change back with it. Content taken to zero keeps its object until the caller runs
     * {@link ReferenceCountService#deleteIfReleased} after commit.
     */
    public ReferenceRepairResult repair(
            UUID assetContentId,
            ToLongFunction<UUID> logicalCount,
            UUID runId,
            String instanceId,
            Instant repairedAt) {
        Objects.requireNonNull(logicalCount, "logicalCount must not be null");
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(repairedAt, "repairedAt must not be null");
        if (instanceId == null || instanceId.isBlank()) {
            throw new IllegalArgumentException("instanceId must not be blank");
        }
        AssetContent content = contents.findByIdForUpdate(assetContentId).orElseThrow(() ->
                new ContentNotFoundException("Asset content not found: " + assetContentId));
        long stored = content.getRefCount();
        long expected = logicalCount.applyAsLong(assetContentId);
        if (expected < 0L) {
            throw new BlobValidationException(
                    "Logical reference count must not be negative for asset content " + assetContentId);
        }
        if (expected == stored) {
            return new ReferenceRepairResult(ReferenceRepairOutcome.CONSISTENT, stored, expected);
        }
        if (stored == 0L) {
            return new ReferenceRepairResult(ReferenceRepairOutcome.RELEASED, stored, expected);
        }
        content.repairRefCount(expected);
        repairs.append(assetContentId, stored, expected, runId, instanceId, repairedAt);
        return new ReferenceRepairResult(ReferenceRepairOutcome.REPAIRED, stored, expected);
    }
}
