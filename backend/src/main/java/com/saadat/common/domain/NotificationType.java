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
    YOUTUBE_VIDEO
}
