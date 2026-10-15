package com.saadat.payments.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.saadat.common.domain.Currency;
import com.saadat.settings.SettingKeys;
import com.saadat.settings.SettingsService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * What an order brought in Egyptian pounds (docs/UX_PAYMENTS_REVIEW_CONTRACT.md §4): EGP orders their amount; other
 * currencies {@code amount × fx[currency] / fx[EGP]} with setting {@code pricing.fx_to_usd} (value of one unit in USD),
 * rounded half-up to 2 places. Unknown rates give null (never a made-up number).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EgpSettlement {

    public static final int SCALE = 2;

    private final SettingsService settingsService;

    /** The current rates (value of one unit in USD), never null. */
    public Map<String, BigDecimal> fxToUsd() {
        try {
            Map<String, BigDecimal> fx = settingsService.getJson(SettingKeys.PRICING_FX_TO_USD,
                    new TypeReference<Map<String, BigDecimal>>() {
                    });
            return fx == null ? Map.of() : fx;
        } catch (RuntimeException e) {
            log.warn("Setting {} is unreadable: {}", SettingKeys.PRICING_FX_TO_USD, e.getMessage());
            return Map.of();
        }
    }

    public BigDecimal toEgp(BigDecimal amount, Currency currency) {
        return toEgp(amount, currency, fxToUsd());
    }

    /** {@code amount} of {@code currency} in EGP with the given rates; null when a rate is missing. */
    public static BigDecimal toEgp(BigDecimal amount, Currency currency, Map<String, BigDecimal> fx) {
        if (amount == null || currency == null) {
            return null;
        }
        if (currency == Currency.EGP) {
            return amount.setScale(SCALE, RoundingMode.HALF_UP);
        }
        BigDecimal rate = fx.get(currency.name());
        BigDecimal egp = fx.get(Currency.EGP.name());
        if (rate == null || egp == null || egp.signum() == 0) {
            return null;
        }
        return amount.multiply(rate).divide(egp, SCALE, RoundingMode.HALF_UP);
    }
}
