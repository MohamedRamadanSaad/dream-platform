package com.saadat.common.domain;

/** Dream status machine (types.ts DreamStatus). Stored as varchar via {@code @Enumerated(EnumType.STRING)}; JSON value equals the constant name. */
public enum DreamStatus {
    DRAFT,
    IN_REVIEW,
    AWAITING_USER_REPLY,
    INTERPRETED,
    CANCELLED
}
