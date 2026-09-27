package com.saadat.dreams.api;

import com.saadat.common.domain.DreamStatus;
import com.saadat.common.domain.Gender;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** types.ts DreamDetail (= DreamSummary + detail fields, flattened). */
public record DreamDetailDto(
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
        InterpretationView interpretation,
        List<DreamMessageDto> messages,
        TestimonialView testimonial,
        CreditRef credit) {

    public record InterpretationView(String text, Instant interpretedAt) {
    }

    public record TestimonialView(int rating, String comment, boolean approved) {
    }

    public record CreditRef(UUID ledgerEntryId, UUID orderId) {
    }
}
