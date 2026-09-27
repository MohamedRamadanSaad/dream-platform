package com.saadat.common.error;

import org.springframework.http.HttpStatus;

/** 409 — state conflict (e.g. invalid status transition, duplicate). */
public class ConflictException extends ApiException {

    public ConflictException(String detail) {
        super(HttpStatus.CONFLICT, "conflict", null, detail);
    }

    public ConflictException(String detail, String code) {
        super(HttpStatus.CONFLICT, "conflict", code, detail);
    }
}
