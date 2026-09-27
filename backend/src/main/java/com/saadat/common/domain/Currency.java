package com.saadat.common.domain;

/**
 * Supported currencies (types.ts Currency). Stored as char(3).
 * NOTE: clashes by simple name with {@link java.util.Currency}; import carefully.
 */
public enum Currency {
    EGP,
    SAR,
    USD
}
