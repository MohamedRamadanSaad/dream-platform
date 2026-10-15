package com.saadat.payments.api;

import com.saadat.common.domain.Currency;
import com.saadat.common.domain.OrderStatus;
import com.saadat.common.domain.PaymentProviderType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * types.ts AdminOrderRow ({@code OrderDto} + buyer + settled EGP + InstaPay transfer) for GET /admin/orders.
 * {@code attachedDreams} = dreams in the order's checkout intent.
 */
public record AdminOrderRow(
        UUID id,
        String orderNumber,
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
        String userName,
        UUID userId,
        String userEmail,
        BigDecimal settledAmountEgp,
        String transferReference,
        String transferSenderName,
        Instant transferSubmittedAt,
        int attachedDreams) {

    public static AdminOrderRow of(OrderDto o, UUID userId, String userName, String userEmail,
                                   BigDecimal settledAmountEgp, String transferReference, String transferSenderName,
                                   Instant transferSubmittedAt, int attachedDreams) {
        return new AdminOrderRow(o.id(), o.orderNumber(), o.packageName(), o.credits(), o.amount(), o.currency(), o.status(),
                o.provider(), o.providerRef(), o.countryCode(), o.createdAt(), o.paidAt(), userName, userId, userEmail,
                settledAmountEgp, transferReference, transferSenderName, transferSubmittedAt, attachedDreams);
    }
}
