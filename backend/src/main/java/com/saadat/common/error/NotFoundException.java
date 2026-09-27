package com.saadat.common.error;

import org.springframework.http.HttpStatus;

/** 404 — resource does not exist or is not visible to the caller. */
public class NotFoundException extends ApiException {

    public NotFoundException(String detail) {
        super(HttpStatus.NOT_FOUND, "not-found", null, detail);
    }

    public static NotFoundException of(String entity, Object id) {
        return new NotFoundException(entity + " not found: " + id);
    }
}
