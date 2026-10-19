package com.edem.dedup4j.historystore;

import java.util.UUID;

/** The deltas one upload (or one compensation) adds to the ledger totals. */
public record UploadContribution(
        UUID assetContentId,
        String hashAlgorithm,
        String contentHash,
        long sizeBytes,
        int uploadDelta,
        int duplicateDelta,
        int physicalWriteDelta,
        long logicalBytesDelta,
        long avoidedBytesDelta,
        Long compensatesSequenceNo) {

    public static UploadContribution completed(
            UUID assetContentId,
            String hashAlgorithm,
            String contentHash,
            long sizeBytes,
            boolean duplicate,
            boolean physicalWrite) {
        return new UploadContribution(assetContentId, hashAlgorithm, contentHash, sizeBytes,
                1, duplicate ? 1 : 0, physicalWrite ? 1 : 0, sizeBytes, duplicate ? sizeBytes : 0, null);
    }

    public static UploadContribution compensating(UploadEvent original) {
        return new UploadContribution(original.getAssetContentId(), original.getHashAlgorithm(),
                original.getContentHash(), original.getSizeBytes(),
                -(int) original.getUploadDelta(), -(int) original.getDuplicateDelta(),
                -(int) original.getPhysicalWriteDelta(), -original.getLogicalBytesDelta(),
                -original.getAvoidedBytesDelta(), original.getSequenceNo());
    }
}
