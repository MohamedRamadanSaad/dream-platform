package com.saadat.pricing.api;

import com.saadat.common.domain.Currency;
import com.saadat.common.domain.PriceScope;
import com.saadat.pricing.domain.PriceRule;
import java.math.BigDecimal;
import java.util.UUID;

/** types.ts PriceRule. */
public record PriceRuleDto(UUID id, PriceScope scope, String scopeId, UUID packageId, BigDecimal price, Currency currency) {

    public static PriceRuleDto from(PriceRule r) {
        return new PriceRuleDto(r.getId(), r.getScope(), r.getScopeId(), r.getPackageId(), r.getPrice(), r.getCurrency());
    }
}
