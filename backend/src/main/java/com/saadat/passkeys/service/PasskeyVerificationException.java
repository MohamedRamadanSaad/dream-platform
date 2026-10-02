package com.saadat.passkeys.service;

/**
 * A WebAuthn response that cannot be trusted (unreadable, wrong challenge/origin/RP, bad signature, counter went
 * backwards...). The message names the failed check for the server log only; callers answer the client with the
 * generic problem code {@link PasskeyService#CODE_INVALID}.
 */
public class PasskeyVerificationException extends RuntimeException {

    public PasskeyVerificationException(String reason) {
        super(reason);
    }

    public PasskeyVerificationException(String reason, Throwable cause) {
        super(reason, cause);
    }
}
