package com.saadat.dreams.api;

import com.saadat.common.domain.Currency;
import com.saadat.common.domain.DreamStatus;
import com.saadat.common.domain.Gender;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * types.ts AdminDreamDetail (= DreamDetail without the user's {@code payment} link + userEditedAt + user + payment
 * (the order that paid this dream), flattened).
 */
public record AdminDreamDetailDto(
        UUID id,
        String excerpt,
        DreamStatus status,
        Instant createdAt,
        Instant submittedAt,
        Instant interpretedAt,
        Instant expectedBy,
        long unreadMessages,
        Instant startedAt,
        Integer expectedDays,
        boolean editable,
        Instant userEditedAt,
        String text,
        Gender gender,
        DreamDetailDto.InterpretationView interpretation,
        List<DreamMessageDto> messages,
        DreamDetailDto.TestimonialView testimonial,
        DreamDetailDto.CreditRef credit,
        UserRef user,
        PaymentRef payment,
        /** While AWAITING_PAYMENT: the order whose approval was taken back (V40); else null. */
        UUID heldOrderId) {

    public record UserRef(UUID id, String name, String email, String countryCode, Integer age) {
    }

    /**
     * The payment that paid this dream. {@code credits} = dreams the package gave, {@code dreamNumber} = this dream's
     * place among them (1 = first), {@code creditsLeft} = dreams the same payment can still pay for.
     */
    public record PaymentRef(
            UUID orderId,
            /** Readable order number, e.g. RN-15001 (V38). */
            String orderNumber,
            String payerName,
            String payerEmail,
            Instant paidAt,
            String packageName,
            BigDecimal amount,
            Currency currency,
            String provider,
            String providerRef,
            String countryCode,
            int credits,
            int creditsLeft,
            int dreamNumber,
            boolean creditsExpired) {
    }

    public static AdminDreamDetailDto of(DreamDetailDto d, Instant userEditedAt, UserRef user, PaymentRef payment,
                                         UUID heldOrderId) {
        return new AdminDreamDetailDto(d.id(), d.excerpt(), d.status(), d.createdAt(), d.submittedAt(),
                d.interpretedAt(), d.expectedBy(), d.unreadMessages(), d.startedAt(), d.expectedDays(), d.editable(),
                userEditedAt, d.text(), d.gender(), d.interpretation(), d.messages(), d.testimonial(), d.credit(), user,
                payment, heldOrderId);
    }
}
