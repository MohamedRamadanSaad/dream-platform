package com.saadat.common.error;

import org.springframework.http.HttpStatus;

/** 401 — missing/invalid credentials (also used for an invalid refresh token). */
public class UnauthorizedException extends ApiException {

    public UnauthorizedException() {
        this("Authentication required");
    }

    public UnauthorizedException(String detail) {
        super(HttpStatus.UNAUTHORIZED, "unauthenticated", null, detail);
    }

    public UnauthorizedException(String detail, String code) {
        super(HttpStatus.UNAUTHORIZED, "unauthenticated", code, detail);
    }
}
