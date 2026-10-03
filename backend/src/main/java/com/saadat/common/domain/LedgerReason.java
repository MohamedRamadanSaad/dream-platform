package com.saadat.common.domain;

/** Credit ledger reason (types.ts CreditLedgerEntry.reason). Stored as varchar via {@code @Enumerated(EnumType.STRING)}; JSON value equals the constant name. */
public enum LedgerReason {
    PURCHASE,
    SUBMIT,
    REFUND,
    MANUAL,
    BONUS,
    /** Unused part of an expired purchase (negative; source_id = that purchase row). */
    EXPIRE
}
