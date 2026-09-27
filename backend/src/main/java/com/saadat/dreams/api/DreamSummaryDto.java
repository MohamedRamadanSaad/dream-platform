package com.saadat.dreams.api;

import com.saadat.common.domain.DreamStatus;
import java.time.Instant;
import java.util.UUID;

/** types.ts DreamSummary. */
public record DreamSummaryDto(
        UUID id,
        String excerpt,
        DreamStatus status,
        Instant createdAt,
        Instant submittedAt,
        Instant interpretedAt,
        Instant expectedBy,
        long unreadMessages) {
}
