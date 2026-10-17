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

    /**
     * Records the redemption for a paid order (idempotent per order). Never fails the payment. A coupon deleted
     * while the order was pending is skipped, as when the database used to null the order's coupon on delete. An
     * order approved again after an undo (V40) restores its revoked redemption instead of adding one. The row
     * remembers whether it counted a use ({@code counted}), so an undo gives back only that.
     */
    @Transactional
    public void redeem(UUID couponId, UUID userId, UUID orderId) {
        if (couponId == null || !couponRepository.existsById(couponId)) {
            return;
        }
        CouponRedemption existing = redemptionRepository.findByOrderId(orderId).orElse(null);
        if (existing != null && existing.getRevokedAt() == null) {
            return;
        }
        boolean counted = couponRepository.incrementUsedCount(couponId) > 0;
        if (!counted) {
            log.info("Coupon {} reached max_uses; redemption recorded without incrementing", couponId);
        }
        CouponRedemption r = existing;
        if (r == null) {
            r = new CouponRedemption();
            r.setCouponId(couponId);
            r.setUserId(userId);
            r.setOrderId(orderId);
            r.setCreatedAt(clock.instant());
        }
        r.setRevokedAt(null);
        r.setCounted(counted);
        redemptionRepository.save(r);
    }

    /**
     * The order's approval was taken back (V40): the use its redemption counted is given back, and the row stays as
     * revoked, so the same user cannot use a single-use coupon again meanwhile. Nothing when it had no redemption.
     */
    @Transactional
    public void revokeRedemption(UUID orderId) {
        if (orderId == null) {
            return;
        }
        redemptionRepository.findByOrderId(orderId).filter(r -> r.getRevokedAt() == null).ifPresent(r -> {
            if (r.isCounted()) {
                couponRepository.decrementUsedCount(r.getCouponId());
            }
            r.setCounted(false);
            r.setRevokedAt(clock.instant());
            redemptionRepository.save(r);
        });
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
