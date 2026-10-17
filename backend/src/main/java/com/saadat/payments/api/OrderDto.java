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
 * types.ts OrderDto. Orders paid through the local mock provider are shown as the provider their currency would
 * use (INSTAPAY for EGP); KASHIER only appears on old test orders. {@code providerRef} is the provider's
 * transaction id once paid. {@code orderNumber} is the readable number shown to people (RN-15001, V38); routes keep
 * using {@code id}.
 */
public record OrderDto(
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
        /** FAILED / SUSPICIOUS orders: why (e.g. TRANSFER_REJECTED, APPROVAL_REVOKED, V40); else null. */
        String failureReason,
        /** Credits of this order withdrawn by the interpreter (V41; a chip on the payment row), else null. */
        Integer leftoverWithdrawnCredits) {

    public static OrderDto from(Order o) {
        return new OrderDto(o.getId(), o.getOrderNumber(), o.getPackageNameSnapshot(), o.getCredits(), o.getAmount(),
                o.getCurrency(),
                o.getStatus(), displayProvider(o), o.getProviderTxnId(), o.getCountryCode(), o.getCreatedAt(),
                o.getPaidAt(), o.getFailureReason(), o.getLeftoverWithdrawnCredits());
    }

    /** The same order shown with another status (an INITIATED order past its expiry is shown EXPIRED, V41). */
    public OrderDto withStatus(OrderStatus newStatus) {
        return new OrderDto(id, orderNumber, packageName, credits, amount, currency, newStatus, provider, providerRef,
                countryCode, createdAt, paidAt, failureReason, leftoverWithdrawnCredits);
    }

    public static PaymentProviderType displayProvider(Order o) {
        return o.getProvider() == PaymentProviderType.MOCK ? ProviderRegistry.nominal(o.getCurrency()) : o.getProvider();
    }
}
