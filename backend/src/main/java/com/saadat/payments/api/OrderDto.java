package com.saadat.payments.api;

import com.saadat.common.domain.Currency;
import com.saadat.common.domain.OrderStatus;
import com.saadat.common.domain.PaymentProviderType;
import com.saadat.payments.domain.Order;
import com.saadat.payments.provider.ProviderRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * types.ts OrderDto. {@code provider} is always PAYMOB | MOR (contract): orders paid through the local mock
 * provider are shown as the gateway their currency would use. {@code providerRef} is the provider's
 * transaction id once paid.
 */
public record OrderDto(
        UUID id,
        String packageName,
        int credits,
        BigDecimal amount,
        Currency currency,
        OrderStatus status,
        PaymentProviderType provider,
        String providerRef,
        String countryCode,
        Instant createdAt,
        Instant paidAt) {

    public static OrderDto from(Order o) {
        return new OrderDto(o.getId(), o.getPackageNameSnapshot(), o.getCredits(), o.getAmount(), o.getCurrency(),
                o.getStatus(), displayProvider(o), o.getProviderTxnId(), o.getCountryCode(), o.getCreatedAt(),
                o.getPaidAt());
    }

    public static PaymentProviderType displayProvider(Order o) {
        return o.getProvider() == PaymentProviderType.MOCK ? ProviderRegistry.nominal(o.getCurrency()) : o.getProvider();
    }
}
