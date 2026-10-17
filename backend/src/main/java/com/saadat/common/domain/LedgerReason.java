package com.saadat.common.domain;

/** Credit ledger reason (types.ts CreditLedgerEntry.reason). Stored as varchar via {@code @Enumerated(EnumType.STRING)}; JSON value equals the constant name. */
public enum LedgerReason {
    PURCHASE,
    SUBMIT,
    REFUND,
    MANUAL,
    BONUS,
    /** Unused part of an expired purchase (negative; source_id = that purchase row). */
    EXPIRE,
    /**
     * Credits of a paid order withdrawn because the interpreter took back its approval (V40; negative, order_id = that
     * order, source_id = the lot it empties: the order's PURCHASE row or a REFUND row of a held dream).
     */
    REVOKE
}
