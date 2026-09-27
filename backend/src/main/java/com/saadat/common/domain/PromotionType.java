package com.saadat.common.domain;

/** Promotion type (types.ts PromotionDto.type). Stored as varchar via {@code @Enumerated(EnumType.STRING)}; JSON value equals the constant name. */
public enum PromotionType {
    PERCENT,
    FIXED,
    BONUS
}
