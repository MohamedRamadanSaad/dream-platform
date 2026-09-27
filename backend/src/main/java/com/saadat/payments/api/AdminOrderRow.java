package com.saadat.payments.api;

import com.saadat.common.domain.Currency;
import com.saadat.common.domain.OrderStatus;
import com.saadat.common.domain.PaymentProviderType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** types.ts {@code OrderDto & { userName }} for GET /admin/orders. */
public record AdminOrderRow(
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
        Instant paidAt,
        String userName) {

    public static AdminOrderRow of(OrderDto o, String userName) {
        return new AdminOrderRow(o.id(), o.packageName(), o.credits(), o.amount(), o.currency(), o.status(),
                o.provider(), o.providerRef(), o.countryCode(), o.createdAt(), o.paidAt(), userName);
    }
}
