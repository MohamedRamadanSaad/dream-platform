package com.saadat.common.domain;

/**
 * What an e-mailed 6-digit code ({@code magic_links.purpose}) is for. A code of one purpose never verifies another:
 * SIGN_IN codes only through /auth/magic/verify, SIGN_UP / RESET_PASSWORD codes only through /auth/code/verify.
 * JSON value equals the constant name.
 */
public enum CodePurpose {
    SIGN_IN,
    SIGN_UP,
    RESET_PASSWORD
}
