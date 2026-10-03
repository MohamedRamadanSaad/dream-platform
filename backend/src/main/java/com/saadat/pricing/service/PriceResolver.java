package com.saadat.pricing.service;

import com.saadat.common.domain.Currency;
import com.saadat.common.domain.PriceScope;
import com.saadat.pricing.domain.Country;
import com.saadat.pricing.domain.PriceRule;
import com.saadat.pricing.repo.CountryRepository;
import com.saadat.pricing.repo.PriceRuleRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Server-side price resolution (contract rule 1): COUNTRY → GROUP → CONTINENT → GLOBAL.
 * The first scope that has a rule for the package wins.
 *
 * <p>Currency rule (owner, 2026-10-03): a visitor only ever sees prices in his country's currency
 * ({@code countries.default_currency}: EGP for Egypt, USD for every other country). Rules in another currency are
 * skipped, so an EGP price set on a continent or a group never reaches a non-Egyptian visitor and a USD fallback
 * never reaches Egypt; a package without a price in that currency is simply not offered there.
 */
@Service
@RequiredArgsConstructor
public class PriceResolver {

    private final CountryRepository countryRepository;
    private final PriceRuleRepository priceRuleRepository;

    /** A resolved list price (before promotions/coupons). */
    public record Resolved(BigDecimal price, Currency currency, PriceScope scope) {
    }

    @Transactional(readOnly = true)
    public Optional<Resolved> resolve(String countryCode, UUID packageId) {
        return pick(findCountry(countryCode).orElse(null), priceRuleRepository.findByPackageId(packageId));
    }

    @Transactional(readOnly = true)
    public Optional<Country> findCountry(String countryCode) {
        if (countryCode == null || countryCode.isBlank()) {
            return Optional.empty();
        }
        return countryRepository.findById(countryCode.trim().toUpperCase(Locale.ROOT));
    }

    /**
     * Pure precedence logic (unit-tested): {@code rules} are the rules of ONE package; {@code country}
     * may be null (unknown country → GLOBAL only).
     */
    public static Optional<Resolved> pick(Country country, List<PriceRule> rules) {
        if (rules == null || rules.isEmpty()) {
            return Optional.empty();
        }
        Currency expected = country == null ? null : country.getDefaultCurrency();
        if (expected != null) {
            rules = rules.stream().filter(r -> r.getCurrency() == expected).toList();
        }
        Optional<PriceRule> hit = Optional.empty();
        if (country != null) {
            hit = find(rules, PriceScope.COUNTRY, country.getCode());
            if (hit.isEmpty() && country.getGroupId() != null) {
                hit = find(rules, PriceScope.GROUP, country.getGroupId().toString());
            }
            if (hit.isEmpty() && country.getContinent() != null) {
                hit = find(rules, PriceScope.CONTINENT, country.getContinent().name());
            }
        }
        if (hit.isEmpty()) {
            hit = find(rules, PriceScope.GLOBAL, null);
        }
        return hit.map(r -> new Resolved(r.getPrice(), r.getCurrency(), r.getScope()));
    }

    private static Optional<PriceRule> find(List<PriceRule> rules, PriceScope scope, String scopeId) {
        return rules.stream()
                .filter(r -> r.getScope() == scope)
                .filter(r -> scopeId == null ? r.getScopeId() == null : scopeId.equalsIgnoreCase(r.getScopeId()))
                .findFirst();
    }
}
