package com.saadat.payments;

import static org.assertj.core.api.Assertions.assertThat;

import com.saadat.common.domain.Currency;
import com.saadat.common.domain.PaymentProviderType;
import com.saadat.payments.provider.ProviderRegistry;
import org.junit.jupiter.api.Test;

class ProviderRoutingTest {

    @Test
    void kashierTakesEgpAndUsdAndNeverSar() {
        assertThat(ProviderRegistry.nominal(Currency.EGP)).isEqualTo(PaymentProviderType.KASHIER);
        assertThat(ProviderRegistry.nominal(Currency.USD)).isEqualTo(PaymentProviderType.KASHIER);
        assertThat(ProviderRegistry.nominal(Currency.SAR)).isEqualTo(PaymentProviderType.MOR);
    }
}
