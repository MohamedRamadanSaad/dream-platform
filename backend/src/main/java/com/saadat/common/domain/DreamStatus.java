package com.saadat.common.domain;

/** Dream status machine (types.ts DreamStatus). Stored as varchar via {@code @Enumerated(EnumType.STRING)}; JSON value equals the constant name. */
public enum DreamStatus {
    DRAFT,
    IN_REVIEW,
    /** The interpreter pressed "Start interpreting": the text is final, {@code expectedDays} is set (V39). */
    IN_PROGRESS,
    AWAITING_USER_REPLY,
    /**
     * The interpreter took back the approval of the InstaPay payment that paid this dream (V40): it is kept (text,
     * messages, startedAt) and waits until that payment is approved again or the dreamer pays it with other credits.
     * Not open work: no SLA, not in the queue counts. {@code heldOrderId} = that order.
     */
    AWAITING_PAYMENT,
    INTERPRETED,
    CANCELLED
}
