package com.saadat.common.domain;

/** In-app notification type (types.ts NotificationType). Stored as varchar via {@code @Enumerated(EnumType.STRING)}; JSON value equals the constant name. */
public enum NotificationType {
    DREAM_SUBMITTED,
    DREAM_RECEIVED,
    INTERPRETER_QUESTION,
    USER_REPLIED,
    INTERPRETATION_READY,
    PAYMENT_SUCCESS,
    PROMOTION,
    YOUTUBE_VIDEO,
    /** To interpreters: an InstaPay transfer waits for confirmation (V34). */
    TRANSFER_SUBMITTED,
    /** To the payer: the interpreter could not find the InstaPay transfer (V34). */
    PAYMENT_REJECTED,
    /** To the dreamer: the interpreter started interpreting, with the expected number of days (V39). */
    DREAM_STARTED,
    /** To interpreters: the dreamer changed the text of a submitted dream before it was started (V39; no e-mail). */
    DREAM_EDITED,
    /**
     * To the payer: the interpreter took back the approval of his InstaPay transfer; his dreams are kept and wait for
     * the payment to be confirmed; he should check and resend the transfer details (V40).
     */
    PAYMENT_REVOKED,
    /** To the payer: the interpreter withdrew the unused credits of one of his payments (V41). */
    CREDITS_WITHDRAWN,
    /** To the payer: withdrawn credits of one of his payments were given back (V41; in-app + push only). */
    CREDITS_RESTORED
}
