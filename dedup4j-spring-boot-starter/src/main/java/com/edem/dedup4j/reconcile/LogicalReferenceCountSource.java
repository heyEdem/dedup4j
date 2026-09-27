package com.edem.dedup4j.reconcile;

import java.util.Map;
import java.util.UUID;

/**
 * Application-owned source of logical asset reference counts.
 *
 * <p>The consuming application decides how to query its logical asset schema;
 * dedup4j only consumes counts keyed by its physical content IDs.</p>
 */
@FunctionalInterface
public interface LogicalReferenceCountSource {

    Map<UUID, Long> countLogicalReferences();
}
