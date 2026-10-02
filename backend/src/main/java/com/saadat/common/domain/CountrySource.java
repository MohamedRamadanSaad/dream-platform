package com.saadat.common.domain;

/** Where a user's/order's country came from: IP = CF-IPCountry or the GeoIP database, HEADER = X-Country (mock only), DEFAULT = setting pricing.default_country, ADMIN = set by the interpreter. Stored as varchar via {@code @Enumerated(EnumType.STRING)}; JSON value equals the constant name. */
public enum CountrySource {
    IP,
    HEADER,
    DEFAULT,
    ADMIN
}
