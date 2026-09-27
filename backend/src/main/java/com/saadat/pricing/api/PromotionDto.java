package com.saadat.pricing.api;

import com.saadat.common.domain.PriceScope;
import com.saadat.common.domain.PromotionType;
import com.saadat.pricing.domain.Promotion;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** types.ts PromotionDto. */
public record PromotionDto(
        UUID id,
        String name,
        List<UUID> packageIds,
        PromotionType type,
        BigDecimal value,
        Instant startsAt,
        Instant endsAt,
        Integer maxUses,
        int usedCount,
        PriceScope scope,
        String scopeId,
        boolean active) {

    public static PromotionDto from(Promotion p) {
        return new PromotionDto(p.getId(), p.getName(), List.copyOf(p.getPackageIds()), p.getType(), p.getValue(),
                p.getStartsAt(), p.getEndsAt(), p.getMaxUses(), p.getUsedCount(), p.getScope(), p.getScopeId(),
                p.isActive());
    }

    public static PromotionDto blank() {
        return new PromotionDto(null, "", List.of(), PromotionType.PERCENT, BigDecimal.ZERO, null, null, null, 0,
                PriceScope.GLOBAL, null, true);
    }
}
