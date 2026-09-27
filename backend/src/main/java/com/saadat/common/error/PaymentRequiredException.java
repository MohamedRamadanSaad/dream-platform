package com.saadat.common.error;

import org.springframework.http.HttpStatus;

/**
 * 402 — not enough credits. Problem body carries {@code code: "INSUFFICIENT_CREDITS"} and
 * {@code missing: <int>}.
 */
public class PaymentRequiredException extends ApiException {

    public static final String CODE = "INSUFFICIENT_CREDITS";

    private final int missing;

    public PaymentRequiredException(int missing) {
        super(HttpStatus.PAYMENT_REQUIRED, "payment-required", null, CODE,
                "Insufficient credits: " + missing + " more required");
        this.missing = missing;
        with("missing", missing);
    }

    public int getMissing() {
        return missing;
    }
}
