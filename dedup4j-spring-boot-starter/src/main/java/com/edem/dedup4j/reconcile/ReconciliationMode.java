package com.edem.dedup4j.reconcile;

/**
 * What a scheduled reconciliation run may do. Only {@link #REPORT} exists: scheduled repair is
 * not offered, so repair stays an explicit
 * {@link CoordinatedRepair#run} call.
 */
public enum ReconciliationMode {
    REPORT
}
