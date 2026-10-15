package com.saadat.payments.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.saadat.common.domain.Currency;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The settled amount in EGP (contract §4), the same formula V39 uses to back-fill:
 * {@code amount × fx[currency] / fx[EGP]}, half-up to 2 places; a missing rate gives null.
 */
class EgpSettlementTest {

    /** The seeded {@code pricing.fx_to_usd}: value of one unit in USD. */
    private static final Map<String, BigDecimal> FX = Map.of(
            "EGP", new BigDecimal("0.0208"), "SAR", new BigDecimal("0.2667"), "USD", BigDecimal.ONE);

    @Test
    void egpIsTheAmountItself() {
        assertThat(EgpSettlement.toEgp(new BigDecimal("300"), Currency.EGP, FX)).isEqualByComparingTo("300.00");
        assertThat(EgpSettlement.toEgp(new BigDecimal("300"), Currency.EGP, Map.of())).isEqualByComparingTo("300.00");
    }

    @Test
    void otherCurrenciesGoThroughTheDollarRates() {
        // 7 USD = 7 × 1 / 0.0208 = 336.538… → 336.54
        assertThat(EgpSettlement.toEgp(new BigDecimal("7"), Currency.USD, FX)).isEqualByComparingTo("336.54");
        // 50 SAR = 50 × 0.2667 / 0.0208 = 641.105… → 641.11
        assertThat(EgpSettlement.toEgp(new BigDecimal("50"), Currency.SAR, FX)).isEqualByComparingTo("641.11");
        // half-up: 0.0104 USD → 0.5 EGP exactly
        assertThat(EgpSettlement.toEgp(new BigDecimal("0.0104"), Currency.USD, FX)).isEqualByComparingTo("0.50");
    }

    @Test
    void aMissingOrZeroRateGivesNull() {
        assertThat(EgpSettlement.toEgp(new BigDecimal("7"), Currency.USD, Map.of("EGP", new BigDecimal("0.0208"))))
                .isNull();
        assertThat(EgpSettlement.toEgp(new BigDecimal("7"), Currency.USD, Map.of("USD", BigDecimal.ONE))).isNull();
        assertThat(EgpSettlement.toEgp(new BigDecimal("7"), Currency.USD,
                Map.of("USD", BigDecimal.ONE, "EGP", BigDecimal.ZERO))).isNull();
        assertThat(EgpSettlement.toEgp(null, Currency.USD, FX)).isNull();
        assertThat(EgpSettlement.toEgp(BigDecimal.TEN, null, FX)).isNull();
    }
}
