package com.saadat.payments.api;

import com.saadat.common.domain.Currency;
import com.saadat.common.domain.OrderStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Bodies of the InstaPay routes (types.ts Instapay*). */
public final class InstapayDtos {

    private InstapayDtos() {
    }

    /**
     * GET /me/orders/{id}/instapay: everything the payment page shows. The receiving account is read from the
     * settings on every request, so a change by the interpreter shows at once.
     */
    public record OrderView(
            UUID orderId,
            /** Readable number shown to the buyer, e.g. RN-15001 (V38). */
            String orderNumber,
            OrderStatus status,
            String packageName,
            int credits,
            BigDecimal amount,
            Currency currency,
            String address,
            String link,
            String recipientName,
            Instant createdAt,
            Instant expiresAt,
            String reference,
            String senderName,
            Instant submittedAt,
            String reviewNote,
            /** The order's intended dreams that are still drafts (sent dreams are counted in heldDreams or not at all). */
            int attachedDreams,
            /** FAILED orders: TRANSFER_REJECTED, or APPROVAL_REVOKED when the interpreter took back her approval (V40). */
            String failureReason,
            /** Dreams kept as AWAITING_PAYMENT until this payment is approved again (V40). */
            int heldDreams,
            /** Last time the interpreter took back the approval (V40); kept after the buyer resends. */
            Instant revokedAt,
            /** The interpreter's note of that undo (V40); kept after the buyer resends. */
            String revokeNote) {
    }

    /**
     * POST /me/orders/{id}/instapay. {@code reference} may hold spaces or Arabic digits (normalised on the server);
     * {@code dreamIds} are the caller's drafts to send to the interpreter once the transfer is confirmed.
     */
    public record SubmitRequest(@NotBlank @Size(max = 64) String reference,
                                @NotBlank @Size(max = 120) String senderName,
                                @Size(max = 10) List<UUID> dreamIds) {
    }

    /** One row of the interpreter's InstaPay review list. */
    public record TransferRow(
            UUID orderId,
            String orderNumber,
            UUID userId,
            String userName,
            String userEmail,
            String packageName,
            int credits,
            BigDecimal amount,
            Currency currency,
            OrderStatus status,
            String reference,
            String senderName,
            Instant createdAt,
            Instant submittedAt,
            Instant reviewedAt,
            String reviewNote,
            int attachedDreams,
            /** TRANSFER_REJECTED / APPROVAL_REVOKED (V40) for FAILED orders, else null. */
            String failureReason,
            /** Dreams waiting (AWAITING_PAYMENT) for this payment to be approved again (V40). */
            int heldDreams,
            /** Last time the interpreter took back the approval (V40); kept after the buyer resends. */
            Instant revokedAt,
            /** The interpreter's note of that undo (V40). */
            String revokeNote) {
    }

    /** GET /admin/payments/instapay/transfers: waiting first (oldest first), then the latest decisions. */
    public record TransferList(List<TransferRow> pending, List<TransferRow> reviewed) {
    }

    public record Counts(long pending) {
    }

    /**
     * POST /admin/payments/instapay/transfers/{id}/approve: the reference the interpreter checked in her InstaPay
     * history. If the buyer changed it meanwhile, the approval is refused (409 REFERENCE_CHANGED).
     */
    public record ApproveRequest(@NotBlank @Size(max = 64) String reference) {
    }

    /** POST /admin/payments/instapay/transfers/{id}/reject. */
    public record RejectRequest(@Size(max = 500) String note) {
    }

    /** POST /admin/payments/instapay/transfers/{id}/revoke: why the approval is taken back (shown to the buyer). */
    public record RevokeRequest(@Size(max = 500) String note) {
    }

    /** GET/PUT /admin/payments/instapay/settings. */
    public record Settings(@NotNull Boolean enabled,
                           @NotBlank @Size(max = 80) String address,
                           @NotBlank @Size(max = 200) String link,
                           @Size(max = 120) String recipientName,
                           @NotNull @Min(1) @Max(168) Integer expireHours) {
    }
}
