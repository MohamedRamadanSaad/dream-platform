package com.saadat.payments.provider;

import com.saadat.common.domain.Currency;
import com.saadat.common.domain.PaymentProviderType;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Picks the provider for a currency: EGP → INSTAPAY while it is on (a manual transfer is safe in every profile, so
 * it wins over the mock); mock (when enabled) → MOCK; EGP and USD → KASHIER; SAR → MOR (Kashier never takes SAR).
 */
@Component
public class ProviderRegistry {

    private final Map<PaymentProviderType, PaymentProvider> providers = new EnumMap<>(PaymentProviderType.class);
    private final MockPaymentProvider mockProvider;
    private final InstapayProvider instapayProvider;

    public ProviderRegistry(List<PaymentProvider> all, MockPaymentProvider mockProvider,
                            InstapayProvider instapayProvider) {
        for (PaymentProvider p : all) {
            providers.put(p.type(), p);
        }
        this.mockProvider = mockProvider;
        this.instapayProvider = instapayProvider;
    }

    public PaymentProvider forCurrency(Currency currency) {
        if (currency == Currency.EGP && instapayProvider.isEnabled()) {
            return instapayProvider;
        }
        if (mockProvider.isEnabled()) {
            return mockProvider;
        }
        return get(nominal(currency));
    }

    public PaymentProvider get(PaymentProviderType type) {
        PaymentProvider p = providers.get(type);
        if (p == null) {
            throw new IllegalStateException("No payment provider bean for " + type);
        }
        return p;
    }

    /** The real gateway for a currency, regardless of mock mode (what the SPA shows as provider). */
    public static PaymentProviderType nominal(Currency currency) {
        return currency == Currency.EGP || currency == Currency.USD ? PaymentProviderType.KASHIER : PaymentProviderType.MOR;
    }
}
