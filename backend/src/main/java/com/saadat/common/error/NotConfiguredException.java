package com.saadat.common.error;

import org.springframework.http.HttpStatus;

/** 503 — an integration (payment provider, push, ...) is not configured in this environment. */
public class NotConfiguredException extends ApiException {

    public NotConfiguredException(String detail) {
        super(HttpStatus.SERVICE_UNAVAILABLE, "not-configured", "NOT_CONFIGURED", detail);
    }
}
