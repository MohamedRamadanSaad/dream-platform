package com.saadat.common.domain;

/** Price rule / promotion scope (types.ts PriceScope). Resolution order: COUNTRY, GROUP, CONTINENT, GLOBAL. Stored as varchar via {@code @Enumerated(EnumType.STRING)}; JSON value equals the constant name. */
public enum PriceScope {
    GLOBAL,
    CONTINENT,
    GROUP,
    COUNTRY
}
