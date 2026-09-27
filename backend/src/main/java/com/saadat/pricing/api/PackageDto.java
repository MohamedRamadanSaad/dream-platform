package com.saadat.pricing.api;

import com.saadat.common.domain.Currency;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** types.ts PackageDto (public catalog entry, localized). */
public record PackageDto(
        UUID id,
        String name,
        String description,
        int credits,
        String badge,
        BigDecimal price,
        BigDecimal originalPrice,
        Currency currency,
        PromotionBadge promotion,
        Integer validityMonths) {

    public record PromotionBadge(String label, Instant endsAt) {
    }
}
