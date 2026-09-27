package com.saadat.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import com.saadat.common.domain.Continent;
import com.saadat.common.domain.CouponType;
import com.saadat.common.domain.Currency;
import com.saadat.common.domain.PriceScope;
import com.saadat.common.domain.PromotionType;
import com.saadat.pricing.domain.Country;
import com.saadat.pricing.domain.Coupon;
import com.saadat.pricing.domain.Promotion;
import com.saadat.pricing.service.CouponService;
import com.saadat.pricing.service.PromotionService;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PromotionAndCouponMathTest {

    private static final Instant NOW = Instant.parse("2026-09-27T10:00:00Z");
    private static final UUID PKG = UUID.randomUUID();
    private static final UUID GULF = UUID.randomUUID();

    // ------------------------------------------------------------------ promotion math

    @Test
    void percentPromotionRoundsToWholeUnits() {
        // 27 × 0.8 = 21.6 → 22 (same as the reference mock's Math.round)
        assertThat(PromotionService.discounted(PromotionType.PERCENT, bd("20"), bd("27"))).isEqualByComparingTo("22");
        assertThat(PromotionService.discounted(PromotionType.PERCENT, bd("20"), bd("349"))).isEqualByComparingTo("279");
    }

    @Test
    void fixedPromotionNeverGoesNegative() {
        assertThat(PromotionService.discounted(PromotionType.FIXED, bd("10"), bd("49"))).isEqualByComparingTo("39");
        assertThat(PromotionService.discounted(PromotionType.FIXED, bd("100"), bd("49"))).isEqualByComparingTo("0");
    }

    @Test
    void bonusPromotionKeepsPriceAndAddsCredits() {
        Promotion p = promo(PromotionType.BONUS, "1", PriceScope.GLOBAL, null);
        PromotionService.Applied a = PromotionService.best(List.of(p), PKG, bd("49"), sa(), NOW);
        assertThat(a.price()).isEqualByComparingTo("49");
        assertThat(a.originalPrice()).isNull();
        assertThat(a.bonusCredits()).isEqualTo(1);
        assertThat(a.promotion()).isSameAs(p);
    }

    @Test
    void appliedPromotionReportsOriginalPrice() {
        PromotionService.Applied a = PromotionService.best(
                List.of(promo(PromotionType.PERCENT, "20", PriceScope.GLOBAL, null)), PKG, bd("27"), sa(), NOW);
        assertThat(a.price()).isEqualByComparingTo("22");
        assertThat(a.originalPrice()).isEqualByComparingTo("27");
    }

    @Test
    void bestPromotionWins() {
        Promotion small = promo(PromotionType.PERCENT, "10", PriceScope.GLOBAL, null);
        Promotion big = promo(PromotionType.FIXED, "20", PriceScope.GLOBAL, null);
        PromotionService.Applied a = PromotionService.best(List.of(small, big), PKG, bd("49"), sa(), NOW);
        assertThat(a.promotion()).isSameAs(big);
        assertThat(a.price()).isEqualByComparingTo("29");
    }

    @Test
    void promotionOutsideWindowOrInactiveOrExhaustedOrOtherPackageDoesNotApply() {
        Promotion future = promo(PromotionType.PERCENT, "20", PriceScope.GLOBAL, null);
        future.setStartsAt(NOW.plus(Duration.ofHours(1)));
        Promotion ended = promo(PromotionType.PERCENT, "20", PriceScope.GLOBAL, null);
        ended.setEndsAt(NOW);
        Promotion inactive = promo(PromotionType.PERCENT, "20", PriceScope.GLOBAL, null);
        inactive.setActive(false);
        Promotion exhausted = promo(PromotionType.PERCENT, "20", PriceScope.GLOBAL, null);
        exhausted.setMaxUses(5);
        exhausted.setUsedCount(5);
        Promotion otherPackage = promo(PromotionType.PERCENT, "20", PriceScope.GLOBAL, null);
        otherPackage.setPackageIds(new ArrayList<>(List.of(UUID.randomUUID())));

        PromotionService.Applied a = PromotionService.best(
                List.of(future, ended, inactive, exhausted, otherPackage), PKG, bd("49"), sa(), NOW);
        assertThat(a.promotion()).isNull();
        assertThat(a.price()).isEqualByComparingTo("49");
        assertThat(a.originalPrice()).isNull();
    }

    @Test
    void promotionScopeMatching() {
        Country sa = sa();
        assertThat(PromotionService.scopeMatches(promo(PromotionType.FIXED, "1", PriceScope.GLOBAL, null), sa)).isTrue();
        assertThat(PromotionService.scopeMatches(promo(PromotionType.FIXED, "1", PriceScope.COUNTRY, "SA"), sa)).isTrue();
        assertThat(PromotionService.scopeMatches(promo(PromotionType.FIXED, "1", PriceScope.COUNTRY, "EG"), sa)).isFalse();
        assertThat(PromotionService.scopeMatches(promo(PromotionType.FIXED, "1", PriceScope.GROUP, GULF.toString()), sa))
                .isTrue();
        assertThat(PromotionService.scopeMatches(
                promo(PromotionType.FIXED, "1", PriceScope.GROUP, UUID.randomUUID().toString()), sa)).isFalse();
        assertThat(PromotionService.scopeMatches(promo(PromotionType.FIXED, "1", PriceScope.CONTINENT, "AS"), sa)).isTrue();
        assertThat(PromotionService.scopeMatches(promo(PromotionType.FIXED, "1", PriceScope.CONTINENT, "EU"), sa)).isFalse();
        assertThat(PromotionService.scopeMatches(promo(PromotionType.FIXED, "1", PriceScope.COUNTRY, "SA"), null)).isFalse();
    }

    // ------------------------------------------------------------------ coupon math

    @Test
    void couponPercentAndFixed() {
        assertThat(CouponService.apply(CouponType.PERCENT, bd("10"), bd("49"))).isEqualByComparingTo("44"); // 44.1
        assertThat(CouponService.apply(CouponType.PERCENT, bd("10"), bd("45"))).isEqualByComparingTo("41"); // 40.5
        assertThat(CouponService.apply(CouponType.FIXED, bd("5"), bd("49"))).isEqualByComparingTo("44");
        assertThat(CouponService.apply(CouponType.FIXED, bd("60"), bd("49"))).isEqualByComparingTo("0");
    }

    @Test
    void couponStacksAfterPromotion() {
        BigDecimal afterPromo = PromotionService.discounted(PromotionType.PERCENT, bd("20"), bd("89")); // 71.2 → 71
        assertThat(CouponService.apply(CouponType.PERCENT, bd("10"), afterPromo)).isEqualByComparingTo("64"); // 63.9
    }

    @Test
    void couponUsability() {
        Coupon c = coupon();
        assertThat(CouponService.isUsable(c, 0, NOW)).isTrue();
        assertThat(CouponService.isUsable(c, 1, NOW)).as("per-user limit 1 reached").isFalse();

        c.setPerUserLimit(2);
        assertThat(CouponService.isUsable(c, 1, NOW)).isTrue();

        c.setExpiresAt(NOW);
        assertThat(CouponService.isUsable(c, 0, NOW)).as("expired").isFalse();
        c.setExpiresAt(null);

        c.setMaxUses(3);
        c.setUsedCount(3);
        assertThat(CouponService.isUsable(c, 0, NOW)).as("max uses").isFalse();
        c.setMaxUses(null);

        c.setActive(false);
        assertThat(CouponService.isUsable(c, 0, NOW)).as("inactive").isFalse();
    }

    // ------------------------------------------------------------------ fixtures

    private static Promotion promo(PromotionType type, String value, PriceScope scope, String scopeId) {
        Promotion p = new Promotion();
        p.setId(UUID.randomUUID());
        p.setName("promo");
        p.setPackageIds(new ArrayList<>(List.of(PKG)));
        p.setType(type);
        p.setValue(bd(value));
        p.setStartsAt(NOW.minus(Duration.ofDays(1)));
        p.setEndsAt(NOW.plus(Duration.ofDays(1)));
        p.setScope(scope);
        p.setScopeId(scopeId);
        p.setActive(true);
        return p;
    }

    private static Coupon coupon() {
        Coupon c = new Coupon();
        c.setId(UUID.randomUUID());
        c.setCode("baraka10");
        c.setType(CouponType.PERCENT);
        c.setValue(bd("10"));
        c.setPerUserLimit(1);
        c.setActive(true);
        return c;
    }

    private static Country sa() {
        Country c = new Country();
        c.setCode("SA");
        c.setNameAr("SA");
        c.setNameEn("SA");
        c.setContinent(Continent.AS);
        c.setDefaultCurrency(Currency.SAR);
        c.setGroupId(GULF);
        return c;
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }
}
