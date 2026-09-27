package com.saadat.common.domain;

/** email_log.status. Stored as varchar via {@code @Enumerated(EnumType.STRING)}; JSON value equals the constant name. */
public enum EmailStatus {
    QUEUED,
    SENT,
    FAILED,
    LOGGED
}
