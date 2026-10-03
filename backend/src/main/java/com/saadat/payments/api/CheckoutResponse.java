package com.saadat.payments.api;

import com.saadat.common.domain.Currency;
import com.saadat.common.domain.PaymentProviderType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** types.ts CheckoutResponse ({@code provider} is KASHIER | MOR). */
public record CheckoutResponse(UUID orderId, PaymentProviderType provider, String checkoutUrl, BigDecimal amount,
                               Currency currency, Instant expiresAt) {
}
