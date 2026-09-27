package com.saadat.common.error;

import org.springframework.http.HttpStatus;

/** 403 — authenticated but not allowed. */
public class ForbiddenException extends ApiException {

    public ForbiddenException(String detail) {
        super(HttpStatus.FORBIDDEN, "forbidden", null, detail);
    }
}
