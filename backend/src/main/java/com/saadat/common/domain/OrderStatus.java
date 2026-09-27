package com.saadat.common.domain;

/** Order status (types.ts OrderStatus). Stored as varchar via {@code @Enumerated(EnumType.STRING)}; JSON value equals the constant name. */
public enum OrderStatus {
    INITIATED,
    SUCCESS,
    FAILED,
    EXPIRED,
    REFUNDED,
    SUSPICIOUS
}
