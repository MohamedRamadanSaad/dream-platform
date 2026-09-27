package com.saadat.dreams.api;

import com.saadat.common.domain.Currency;
import com.saadat.common.domain.DreamStatus;
import com.saadat.common.domain.Gender;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** types.ts AdminDreamDetail (= DreamDetail + user + payment, flattened). */
public record AdminDreamDetailDto(
        UUID id,
        String excerpt,
        DreamStatus status,
        Instant createdAt,
        Instant submittedAt,
        Instant interpretedAt,
        Instant expectedBy,
        long unreadMessages,
        String text,
        Gender gender,
        DreamDetailDto.InterpretationView interpretation,
        List<DreamMessageDto> messages,
        DreamDetailDto.TestimonialView testimonial,
        DreamDetailDto.CreditRef credit,
        UserRef user,
        PaymentRef payment) {

    public record UserRef(UUID id, String name, String email, String countryCode) {
    }

    public record PaymentRef(
            UUID orderId,
            String payerName,
            String payerEmail,
            Instant paidAt,
            String packageName,
            BigDecimal amount,
            Currency currency,
            String provider,
            String providerRef,
            String countryCode) {
    }

    public static AdminDreamDetailDto of(DreamDetailDto d, UserRef user, PaymentRef payment) {
        return new AdminDreamDetailDto(d.id(), d.excerpt(), d.status(), d.createdAt(), d.submittedAt(),
                d.interpretedAt(), d.expectedBy(), d.unreadMessages(), d.text(), d.gender(), d.interpretation(),
                d.messages(), d.testimonial(), d.credit(), user, payment);
    }
}
