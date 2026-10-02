package com.saadat.passkeys.domain;

/** What a passkey challenge was issued for (column passkey_challenges.purpose). */
public enum PasskeyPurpose {
    /** Adding a passkey to the signed-in account (POST /me/passkeys/registration/options). */
    REGISTRATION,
    /** Signing in with a passkey (POST /auth/passkey/options). */
    AUTHENTICATION
}
