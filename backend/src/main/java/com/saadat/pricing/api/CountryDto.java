package com.saadat.pricing.api;

import com.saadat.common.domain.Continent;
import com.saadat.common.domain.Currency;
import com.saadat.pricing.domain.Country;
import java.util.UUID;

/** types.ts CountryDto. */
public record CountryDto(String code, String nameAr, String nameEn, Continent continent, Currency defaultCurrency,
                         UUID groupId) {

    public static CountryDto from(Country c) {
        return new CountryDto(c.getCode(), c.getNameAr(), c.getNameEn(), c.getContinent(), c.getDefaultCurrency(),
                c.getGroupId());
    }
}
