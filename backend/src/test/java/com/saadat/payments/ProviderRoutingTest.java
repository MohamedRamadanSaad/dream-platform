package com.saadat.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.saadat.common.domain.Currency;
import com.saadat.common.domain.PaymentProviderType;
import com.saadat.common.error.ValidationException;
import com.saadat.payments.provider.InstapayProvider;
import com.saadat.payments.provider.MockPaymentProvider;
import com.saadat.payments.provider.ProviderRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;

/** For now payment is taken inside Egypt only (InstaPay); the rest is refused in production. */
class ProviderRoutingTest {

    @Test
    void egyptPaysByInstapayAndTheRestIsRefusedInProduction() {
        InstapayProvider instapay = mock(InstapayProvider.class);
        MockPaymentProvider mockProvider = mock(MockPaymentProvider.class);
        when(instapay.isEnabled()).thenReturn(true);
        when(instapay.type()).thenReturn(PaymentProviderType.INSTAPAY);
        when(mockProvider.isEnabled()).thenReturn(false);
        when(mockProvider.type()).thenReturn(PaymentProviderType.MOCK);
        ProviderRegistry registry = new ProviderRegistry(List.of(instapay, mockProvider), mockProvider, instapay);

        assertThat(registry.forCurrency(Currency.EGP)).isSameAs(instapay);
        assertThatThrownBy(() -> registry.forCurrency(Currency.USD))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("inside Egypt only");

        // InstaPay switched off: Egypt is refused too (until the bank gateway exists)
        when(instapay.isEnabled()).thenReturn(false);
        assertThatThrownBy(() -> registry.forCurrency(Currency.EGP)).isInstanceOf(ValidationException.class);
    }

    @Test
    void mockOrdersAreShownAsTheirProductionProvider() {
        assertThat(ProviderRegistry.nominal(Currency.EGP)).isEqualTo(PaymentProviderType.INSTAPAY);
        assertThat(ProviderRegistry.nominal(Currency.USD)).isEqualTo(PaymentProviderType.MOR);
    }
}
