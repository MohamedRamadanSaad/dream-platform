package com.saadat.pricing.service;

import com.saadat.common.domain.PromotionType;
import com.saadat.pricing.domain.Country;
import com.saadat.pricing.domain.DreamPackage;
import com.saadat.pricing.domain.Promotion;
import com.saadat.pricing.repo.PromotionRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies the best active promotion to a resolved price. A promotion applies when it is active, now is
 * inside [startsAt, endsAt), the package is listed, max_uses is not exhausted and its scope matches the
 * country (GLOBAL always; COUNTRY by code; GROUP by the country's group id; CONTINENT by continent).
 * PERCENT results are rounded to whole units (same as the reference mock).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PromotionService {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final PromotionRepository promotionRepository;
    private final Clock clock;

    /**
     * @param price         price after promotion (unchanged for BONUS)
     * @param originalPrice list price when the promotion lowered it, else null
     * @param promotion     the applied promotion or null
     * @param bonusCredits  extra credits granted by a BONUS promotion (0 otherwise)
     */
    public record Applied(BigDecimal price, BigDecimal originalPrice, Promotion promotion, int bonusCredits) {

        public static Applied none(BigDecimal price) {
            return new Applied(price, null, null, 0);
        }
    }

    @Transactional(readOnly = true)
    public Applied apply(DreamPackage pkg, BigDecimal price, Country country) {
        Instant now = clock.instant();
        return best(promotionRepository.findActiveAt(now), pkg.getId(), price, country, now);
    }

    /**
     * Counts one use (on successful payment). Never fails the caller when max_uses was reached meanwhile. A promotion
     * deleted while the order was pending is skipped.
     */
    @Transactional
    public void recordUse(UUID promotionId) {
        if (promotionId == null || !promotionRepository.existsById(promotionId)) {
            return;
        }
        if (promotionRepository.incrementUsedCount(promotionId) == 0) {
            log.info("Promotion {} reached max_uses; use not counted", promotionId);
        }
    }

    // ------------------------------------------------------------------ pure logic (unit-tested)

    public static Applied best(List<Promotion> promotions, UUID packageId, BigDecimal price, Country country,
                               Instant now) {
        Applied best = Applied.none(price);
        if (promotions == null) {
            return best;
        }
        for (Promotion p : promotions) {
            if (!isApplicable(p, packageId, country, now)) {
                continue;
            }
            BigDecimal discounted = discounted(p.getType(), p.getValue(), price);
            int bonus = p.getType() == PromotionType.BONUS ? p.getValue().intValue() : 0;
            boolean better = best.promotion() == null
                    || discounted.compareTo(best.price()) < 0
                    || (discounted.compareTo(best.price()) == 0 && bonus > best.bonusCredits());
            if (better) {
                BigDecimal original = discounted.compareTo(price) < 0 ? price : null;
                best = new Applied(discounted, original, p, bonus);
            }
        }
        return best;
    }

    public static boolean isApplicable(Promotion p, UUID packageId, Country country, Instant now) {
        if (!p.isActive() || p.getStartsAt() == null || p.getEndsAt() == null) {
            return false;
        }
        if (now.isBefore(p.getStartsAt()) || !now.isBefore(p.getEndsAt())) {
            return false;
        }
        if (p.getPackageIds() == null || !p.getPackageIds().contains(packageId)) {
            return false;
        }
        if (p.getMaxUses() != null && p.getUsedCount() >= p.getMaxUses()) {
            return false;
        }
        return scopeMatches(p, country);
    }

    public static boolean scopeMatches(Promotion p, Country country) {
        if (p.getScope() == null) {
            return true;
        }
        return switch (p.getScope()) {
            case GLOBAL -> true;
            case COUNTRY -> country != null && country.getCode().equalsIgnoreCase(p.getScopeId());
            case GROUP -> country != null && country.getGroupId() != null
                    && country.getGroupId().toString().equalsIgnoreCase(p.getScopeId());
            case CONTINENT -> country != null && country.getContinent() != null
                    && country.getContinent().name().equalsIgnoreCase(p.getScopeId());
        };
    }

    public static BigDecimal discounted(PromotionType type, BigDecimal value, BigDecimal price) {
        if (type == null || value == null) {
            return price;
        }
        BigDecimal result = switch (type) {
            case PERCENT -> price.multiply(BigDecimal.ONE.subtract(value.divide(HUNDRED)))
                    .setScale(0, RoundingMode.HALF_UP);
            case FIXED -> price.subtract(value);
            case BONUS -> price;
        };
        return result.signum() < 0 ? BigDecimal.ZERO : result;
    }
}
