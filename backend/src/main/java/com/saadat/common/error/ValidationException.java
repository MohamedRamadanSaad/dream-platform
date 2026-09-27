package com.saadat.common.error;

import org.springframework.http.HttpStatus;

/**
 * 422 — semantically invalid request (e.g. draft limit reached, invalid coupon).
 * Bean-validation failures are 400 and handled separately.
 */
public class ValidationException extends ApiException {

    public ValidationException(String detail) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "unprocessable-entity", null, detail);
    }

    public ValidationException(String detail, String code) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "unprocessable-entity", code, detail);
    }
}
