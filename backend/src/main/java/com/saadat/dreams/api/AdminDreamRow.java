package com.saadat.dreams.api;

import com.saadat.common.domain.DreamStatus;
import com.saadat.common.domain.Gender;
import java.time.Instant;
import java.util.UUID;

/** types.ts AdminDreamRow. */
public record AdminDreamRow(
        UUID id,
        UUID userId,
        String userName,
        Gender gender,
        String excerpt,
        DreamStatus status,
        Instant submittedAt,
        Instant slaDeadline,
        boolean overdue,
        String countryCode,
        Instant startedAt,
        Integer expectedDays,
        Instant userEditedAt,
        /** AWAITING_PAYMENT (V40): the order whose approval was taken back, and when the hold started; else null. */
        UUID heldOrderId,
        Instant heldAt) {
}
