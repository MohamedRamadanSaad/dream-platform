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
    PAYMENT_REJECTED
}
