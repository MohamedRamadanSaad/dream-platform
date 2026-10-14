package com.saadat.payments.provider;

import com.saadat.common.domain.Currency;
import com.saadat.common.domain.PaymentProviderType;
import com.saadat.common.error.ValidationException;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Picks the provider for a currency. For now payment is taken inside Egypt only: EGP → INSTAPAY while it is on (a
 * manual transfer is safe in every profile, so it wins over the mock); outside production the mock takes the rest;
 * otherwise checkout is refused with 422 PAYMENT_EGYPT_ONLY before any order is saved. A bank gateway (NBE) will be
 * added here once it is approved.
 */
@Component
public class ProviderRegistry {

    /** Checkout refused: no payment method outside Egypt yet. */
    public static final String CODE_EGYPT_ONLY = "PAYMENT_EGYPT_ONLY";

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
        throw new ValidationException("Payment is available inside Egypt only for now", CODE_EGYPT_ONLY);
    }

    public PaymentProvider get(PaymentProviderType type) {
        PaymentProvider p = providers.get(type);
        if (p == null) {
            throw new IllegalStateException("No payment provider bean for " + type);
        }
        return p;
    }

    /** How an order paid through the local mock is shown: the provider its currency would use in production. */
    public static PaymentProviderType nominal(Currency currency) {
        return currency == Currency.EGP ? PaymentProviderType.INSTAPAY : PaymentProviderType.MOR;
    }
}
