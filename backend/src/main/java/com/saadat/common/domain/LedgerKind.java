package com.saadat.common.domain;

/**
 * What a REVOKE / REFUND ledger row was for (V41; types.ts CreditLedgerEntry.kind), so the credit history can tell an
 * undone approval from a withdrawn or restored leftover. Null on every other row.
 */
public enum LedgerKind {
    /** The interpreter took back the approval of the order (V40): its REVOKE rows and the REFUND of held dreams. */
    APPROVAL_REVOKED,
    /** The unused credits of a paid order were withdrawn (REVOKE rows, V41). */
    LEFTOVER_WITHDRAWN,
    /** Withdrawn credits given back to the order's lot (REFUND rows with the lot's original expiry, V41). */
    LEFTOVER_RESTORED
}
