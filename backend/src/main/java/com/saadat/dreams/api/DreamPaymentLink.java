package com.saadat.dreams.api;

import java.time.Instant;
import java.util.UUID;

/**
 * types.ts DreamPaymentLink: the payment a draft is attached to (docs/UX_PAYMENTS_REVIEW_CONTRACT.md §2).
 * {@code status} is {@link #PENDING_REVIEW} (the transfer waits for the interpreter) or {@link #REJECTED} (the
 * interpreter found no matching transfer; the buyer may correct it).
 */
public record DreamPaymentLink(UUID orderId, String status, Instant submittedAt, String reviewNote) {

    public static final String PENDING_REVIEW = "PENDING_REVIEW";
    public static final String REJECTED = "REJECTED";
}
