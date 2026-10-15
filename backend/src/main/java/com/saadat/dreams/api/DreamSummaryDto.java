package com.saadat.dreams.api;

import com.saadat.common.domain.DreamStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * types.ts DreamSummary. {@code editable}: DRAFT, or IN_REVIEW before the interpreter started; {@code payment}: the
 * payment a draft is attached to (docs/UX_PAYMENTS_REVIEW_CONTRACT.md §2), else null.
 */
public record DreamSummaryDto(
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
        DreamPaymentLink payment) {
}
