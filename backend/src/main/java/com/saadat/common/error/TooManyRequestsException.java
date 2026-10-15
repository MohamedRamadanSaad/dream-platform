package com.saadat.common.error;

import org.springframework.http.HttpStatus;

/**
 * 429 — too many attempts (e.g. wrong passwords). Problem body carries {@code code} and
 * {@code retryAfterSeconds: <long>} (seconds until the next attempt is allowed).
 */
public class TooManyRequestsException extends ApiException {

    public static final String PROP_RETRY_AFTER_SECONDS = "retryAfterSeconds";

    private final long retryAfterSeconds;

    public TooManyRequestsException(String detail, String code, long retryAfterSeconds) {
        super(HttpStatus.TOO_MANY_REQUESTS, "too-many-requests", code, detail);
        this.retryAfterSeconds = retryAfterSeconds;
        with(PROP_RETRY_AFTER_SECONDS, retryAfterSeconds);
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
