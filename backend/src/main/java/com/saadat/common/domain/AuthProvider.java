package com.saadat.common.domain;

/** Login provider (types.ts AuthProvider). Stored as varchar via {@code @Enumerated(EnumType.STRING)}; JSON value equals the constant name. */
public enum AuthProvider {
    GOOGLE,
    MAGIC_LINK
}
