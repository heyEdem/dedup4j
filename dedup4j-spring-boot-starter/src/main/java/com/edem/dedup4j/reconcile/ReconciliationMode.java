package com.edem.dedup4j.reconcile;

/** What a scheduled reconciliation run does. */
public enum ReconciliationMode {
    /** Reports drift through {@link ReconciliationService#summarize}; never writes. */
    REPORT,
    /**
     * Repairs drift through {@link CoordinatedRepair#run}. Only correct when every writer, on every
     * instance, follows the writer contract on {@link ContentReferenceCountSource}.
     */
    REPAIR
}
