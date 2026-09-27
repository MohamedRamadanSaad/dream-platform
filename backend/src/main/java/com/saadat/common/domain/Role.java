package com.saadat.common.domain;

/** User role (types.ts Role). Stored as varchar via {@code @Enumerated(EnumType.STRING)}; JSON value equals the constant name. */
public enum Role {
    USER,
    INTERPRETER
}
