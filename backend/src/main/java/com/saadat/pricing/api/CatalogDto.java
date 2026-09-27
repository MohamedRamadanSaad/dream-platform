package com.saadat.pricing.api;

import com.saadat.common.domain.Currency;
import java.util.List;

/** types.ts Catalog. */
public record CatalogDto(String countryCode, String countryName, Currency currency, List<PackageDto> packages) {
}
