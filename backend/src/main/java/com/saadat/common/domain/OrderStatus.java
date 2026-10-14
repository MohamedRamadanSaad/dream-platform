package com.saadat.common.domain;

/** Order status (types.ts OrderStatus). Stored as varchar via {@code @Enumerated(EnumType.STRING)}; JSON value equals the constant name. */
public enum OrderStatus {
    INITIATED,
    /** InstaPay: the user sent the transfer details; the interpreter checks the money arrived (V34). */
    PENDING_REVIEW,
    SUCCESS,
    FAILED,
    EXPIRED,
    REFUNDED,
    SUSPICIOUS
}
