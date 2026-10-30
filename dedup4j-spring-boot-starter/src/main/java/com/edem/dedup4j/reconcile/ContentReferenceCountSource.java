package com.edem.dedup4j.reconcile;

import java.util.UUID;

/**
 * The application's authoritative reference count for one content, read by
 * {@link CoordinatedRepair} inside its repair transaction after the content row is locked.
 *
 * <p>Repair only stays correct when the application follows this writer contract, on every
 * instance and for every path that adds, removes or moves a reference (deletes and cascades
 * included): the dedup4j count change and the application's own reference row commit in
 * <strong>one transaction</strong>, so the content row lock taken by
 * {@code TransactionalBlobReferences}, {@code ReferenceCountService.retain}/{@code release} or the
 * raw {@code DefaultBlobDeduplicationService} is held until both are visible. The auto-configured
 * {@code BlobStore} and {@code BlobDeduplicationService} commit the count in their own transaction
 * and do not meet this contract.</p>
 *
 * <p>The implementation must run a plain, uncached count against the primary database through the
 * transaction it is called in (the shared {@code EntityManager} or the transaction's connection),
 * cover every reference to the content, and take no locks. A replica, cache, another transaction
 * manager or a filtered view breaks the guarantee. Throwing aborts the repair of that content.</p>
 */
@FunctionalInterface
public interface ContentReferenceCountSource {

    long countLogicalReferences(UUID assetContentId);
}
