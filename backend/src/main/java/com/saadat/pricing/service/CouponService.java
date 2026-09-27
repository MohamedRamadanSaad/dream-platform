package com.saadat.pricing.service;

import com.saadat.common.domain.CouponType;
import com.saadat.common.error.ValidationException;
import com.saadat.pricing.domain.Coupon;
import com.saadat.pricing.domain.CouponRedemption;
import com.saadat.pricing.repo.CouponRedemptionRepository;
import com.saadat.pricing.repo.CouponRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Coupon validation at checkout (active, not expired, max_uses, per-user limit) and redemption on
 * successful payment. Invalid coupons → 422 with code {@value #CODE_INVALID}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CouponService {

    public static final String CODE_INVALID = "INVALID_COUPON";
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final CouponRepository couponRepository;
    private final CouponRedemptionRepository redemptionRepository;
    private final Clock clock;

    @Transactional(readOnly = true)
    public Coupon validate(String code, UUID userId) {
        if (code == null || code.isBlank()) {
            throw invalid();
        }
        Coupon coupon = couponRepository.findByCode(code.trim().toUpperCase(Locale.ROOT)).orElseThrow(CouponService::invalid);
        long userRedemptions = redemptionRepository.countByCouponIdAndUserId(coupon.getId(), userId);
        if (!isUsable(coupon, userRedemptions, clock.instant())) {
            throw invalid();
        }
        return coupon;
    }

    /** Records the redemption for a paid order (idempotent per order). Never fails the payment. */
    @Transactional
    public void redeem(UUID couponId, UUID userId, UUID orderId) {
        if (couponId == null || redemptionRepository.existsByOrderId(orderId)) {
            return;
        }
        if (couponRepository.incrementUsedCount(couponId) == 0) {
            log.info("Coupon {} reached max_uses; redemption recorded without incrementing", couponId);
        }
        CouponRedemption r = new CouponRedemption();
        r.setCouponId(couponId);
        r.setUserId(userId);
        r.setOrderId(orderId);
        r.setCreatedAt(clock.instant());
        redemptionRepository.save(r);
    }

    // ------------------------------------------------------------------ pure logic (unit-tested)

    public static boolean isUsable(Coupon c, long userRedemptions, Instant now) {
        if (!c.isActive()) {
            return false;
        }
        if (c.getExpiresAt() != null && !now.isBefore(c.getExpiresAt())) {
            return false;
        }
        if (c.getMaxUses() != null && c.getUsedCount() >= c.getMaxUses()) {
            return false;
        }
        return userRedemptions < c.getPerUserLimit();
    }

    /** PERCENT is rounded to whole units (same as the reference mock); the result is never negative. */
    public static BigDecimal apply(CouponType type, BigDecimal value, BigDecimal price) {
        if (type == null || value == null) {
            return price;
        }
        BigDecimal result = switch (type) {
            case PERCENT -> price.multiply(BigDecimal.ONE.subtract(value.divide(HUNDRED)))
                    .setScale(0, RoundingMode.HALF_UP);
            case FIXED -> price.subtract(value);
        };
        return result.signum() < 0 ? BigDecimal.ZERO : result;
    }

    public static BigDecimal apply(Coupon coupon, BigDecimal price) {
        return apply(coupon.getType(), coupon.getValue(), price);
    }

    private static ValidationException invalid() {
        return new ValidationException("Invalid coupon", CODE_INVALID);
    }
}
