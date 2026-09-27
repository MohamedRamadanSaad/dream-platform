package com.saadat.pricing.api;

import com.saadat.common.domain.CouponType;
import com.saadat.pricing.domain.Coupon;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** types.ts CouponDto. */
public record CouponDto(
        UUID id,
        String code,
        CouponType type,
        BigDecimal value,
        Integer maxUses,
        int perUserLimit,
        int usedCount,
        Instant expiresAt,
        boolean active) {

    public static CouponDto from(Coupon c) {
        return new CouponDto(c.getId(), c.getCode(), c.getType(), c.getValue(), c.getMaxUses(), c.getPerUserLimit(),
                c.getUsedCount(), c.getExpiresAt(), c.isActive());
    }

    public static CouponDto blank() {
        return new CouponDto(null, "", CouponType.PERCENT, BigDecimal.ZERO, null, 1, 0, null, true);
    }
}
