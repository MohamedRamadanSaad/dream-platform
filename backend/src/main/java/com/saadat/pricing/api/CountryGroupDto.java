package com.saadat.pricing.api;

import java.util.List;
import java.util.UUID;

/** types.ts CountryGroup. */
public record CountryGroupDto(UUID id, String name, List<String> countryCodes) {
}
