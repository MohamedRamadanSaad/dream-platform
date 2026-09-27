package com.saadat.common.domain;

/** Coupon type (types.ts CouponDto.type). Stored as varchar via {@code @Enumerated(EnumType.STRING)}; JSON value equals the constant name. */
public enum CouponType {
    PERCENT,
    FIXED
}
