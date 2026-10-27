package com.edem.dedup4j.reconcile;

/**
 * What a scheduled reconciliation run may do. Only {@link #REPORT} exists: scheduled repair is
 * unsafe while the application is writing references, so repair stays the explicit
 * {@link ReconciliationService#repair(LogicalReferenceCountSource)} call.
 */
public enum ReconciliationMode {
    REPORT
}
