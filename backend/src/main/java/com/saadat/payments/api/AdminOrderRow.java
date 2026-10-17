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
        int attachedDreams,
        /** FAILED orders: TRANSFER_REJECTED / APPROVAL_REVOKED / provider reasons; else null. */
        String failureReason,
        /** Last time the interpreter took back the approval (V40); kept after the buyer resends. */
        Instant revokedAt,
        /** The interpreter's note of that undo (V40). */
        String revokeNote,
        /** Dreams waiting (AWAITING_PAYMENT) for this order to be approved again (V40). */
        int heldDreams,
        /** Until when an unpaid booking can be paid (V41: "expires in …" / "expired" chip). */
        Instant expiresAt,
        /** The package bought (null when the package was deleted). */
        UUID packageId,
        /** When the interpreter withdrew the unused credits of this order (V41); else null. */
        Instant leftoverWithdrawnAt) {

    public static AdminOrderRow of(OrderDto o, UUID userId, String userName, String userEmail,
                                   BigDecimal settledAmountEgp, String transferReference, String transferSenderName,
                                   Instant transferSubmittedAt, int attachedDreams, String failureReason,
                                   Instant revokedAt, String revokeNote, int heldDreams, Instant expiresAt,
                                   UUID packageId, Instant leftoverWithdrawnAt) {
        return new AdminOrderRow(o.id(), o.orderNumber(), o.packageName(), o.credits(), o.amount(), o.currency(), o.status(),
                o.provider(), o.providerRef(), o.countryCode(), o.createdAt(), o.paidAt(), userName, userId, userEmail,
                settledAmountEgp, transferReference, transferSenderName, transferSubmittedAt, attachedDreams,
                failureReason, revokedAt, revokeNote, heldDreams, expiresAt, packageId, leftoverWithdrawnAt);
    }
}
