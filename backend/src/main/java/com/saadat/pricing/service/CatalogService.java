package com.saadat.pricing.service;

import com.saadat.common.domain.Currency;
import com.saadat.common.domain.Locale;
import com.saadat.pricing.api.CatalogDto;
import com.saadat.pricing.api.PackageDto;
import com.saadat.pricing.domain.Country;
import com.saadat.pricing.domain.DreamPackage;
import com.saadat.pricing.repo.DreamPackageRepository;
import com.saadat.settings.SettingKeys;
import com.saadat.settings.SettingsService;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Builds the public catalog for a country: list price → best promotion, localized names. */
@Service
@RequiredArgsConstructor
public class CatalogService {

    private final DreamPackageRepository packageRepository;
    private final PriceResolver priceResolver;
    private final PromotionService promotionService;
    private final SettingsService settingsService;

    @Transactional(readOnly = true)
    public CatalogDto catalog(String countryCode, Locale locale) {
        Country country = priceResolver.findCountry(countryCode).orElse(null);
        boolean en = locale == Locale.EN;
        List<PackageDto> packages = new ArrayList<>();
        for (DreamPackage p : packageRepository.findByActiveTrueOrderBySortOrderAsc()) {
            Optional<PriceResolver.Resolved> resolved = priceResolver.resolve(countryCode, p.getId());
            if (resolved.isEmpty()) {
                continue; // no rule at any scope → not purchasable
            }
            PriceResolver.Resolved r = resolved.get();
            PromotionService.Applied a = promotionService.apply(p, r.price(), country);
            PackageDto.PromotionBadge badge = a.promotion() == null ? null
                    : new PackageDto.PromotionBadge(a.promotion().getName(), a.promotion().getEndsAt());
            packages.add(new PackageDto(
                    p.getId(),
                    en ? p.getNameEn() : p.getNameAr(),
                    en ? p.getDescriptionEn() : p.getDescriptionAr(),
                    p.getCredits() + a.bonusCredits(),
                    p.getBadge(),
                    a.price(),
                    a.originalPrice(),
                    r.currency(),
                    badge,
                    p.getValidityMonths()));
        }
        Currency currency = packages.isEmpty()
                ? (country != null ? country.getDefaultCurrency() : globalCurrency())
                : packages.get(0).currency();
        String code = country != null ? country.getCode() : countryCode;
        String name = country == null ? countryCode : (en ? country.getNameEn() : country.getNameAr());
        return new CatalogDto(code, name, currency, packages);
    }

    private Currency globalCurrency() {
        return Currency.valueOf(settingsService.getString(SettingKeys.PRICING_GLOBAL_CURRENCY).trim());
    }
}
