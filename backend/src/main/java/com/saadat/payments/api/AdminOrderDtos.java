package com.saadat.payments.api;

import com.saadat.common.domain.CountrySource;
import com.saadat.common.domain.Currency;
import com.saadat.common.domain.DreamStatus;
import com.saadat.common.domain.OrderStatus;
import com.saadat.common.domain.PaymentProviderType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The interpreter's payments page (docs/UX_PAYMENTS_REVIEW_CONTRACT.md §3). */
public final class AdminOrderDtos {

    private AdminOrderDtos() {
    }

    /** types.ts OrderScope: the tabs of /admin/orders. */
    public enum Scope {
        /** InstaPay transfers waiting for the interpreter, oldest submitted first. */
        REVIEW,
        /** Orders from Egypt, newest first. */
        DOMESTIC,
        /** Orders from outside Egypt (or without a country), newest first. */
        INTERNATIONAL,
        /** Every order, newest first (CSV export). */
        ALL
    }

    /** types.ts AdminOrderCounts. */
    public record Counts(long review, long domestic, long international) {
    }

    /** types.ts AdminOrderDetail. */
    public record Detail(
            UUID id,
            String orderNumber,
            OrderStatus status,
            String failureReason,
            PaymentProviderType provider,
            String providerRef,
            String packageName,
            int credits,
            BigDecimal amount,
            Currency currency,
            BigDecimal settledAmountEgp,
            BigDecimal discount,
            String couponCode,
            String countryCode,
            CountrySource countrySource,
            Instant createdAt,
            Instant paidAt,
            Instant expiresAt,
            Transfer transfer,
            UserRef user,
            List<DreamRef> dreams,
            boolean canReview,
            /** INSTAPAY && SUCCESS && none of its dreams interpreted: the approval may be taken back (V40). */
            boolean canRevoke,
            /** Why an approved InstaPay order cannot be taken back: DREAM_ALREADY_INTERPRETED, else null. */
            String revokeBlockedReason,
            /** Last time the interpreter took back the approval (V40); kept after the buyer resends. */
            Instant revokedAt,
            /** The interpreter's note of that undo (V40). */
            String revokeNote,
            /** Dreams waiting (AWAITING_PAYMENT) for this order to be approved again (V40). */
            int heldDreams) {
    }

    /** The InstaPay transfer the buyer reported, and the interpreter's decision. */
    public record Transfer(String reference, String senderName, Instant submittedAt, Instant reviewedAt,
                           String reviewedByName, String reviewNote) {
    }

    public record UserRef(UUID id, String name, String email, String countryCode, Integer age) {
    }

    /** A dream of the order: in its checkout intent, or paid by its credits. Full text (the interpreter reads it). */
    public record DreamRef(UUID id, DreamStatus status, String text, Instant createdAt, Instant submittedAt) {
    }
}
