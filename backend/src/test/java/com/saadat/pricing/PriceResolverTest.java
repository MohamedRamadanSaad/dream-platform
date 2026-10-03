package com.saadat.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import com.saadat.common.domain.Continent;
import com.saadat.common.domain.Currency;
import com.saadat.common.domain.PriceScope;
import com.saadat.pricing.domain.Country;
import com.saadat.pricing.domain.PriceRule;
import com.saadat.pricing.service.PriceResolver;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Precedence: COUNTRY > GROUP > CONTINENT > GLOBAL. */
class PriceResolverTest {

    private static final UUID PKG = UUID.randomUUID();
    private static final UUID GULF = UUID.randomUUID();

    private static final PriceRule GLOBAL = rule(PriceScope.GLOBAL, null, "15", Currency.USD);
    private static final PriceRule EUROPE = rule(PriceScope.CONTINENT, "EU", "19", Currency.USD);
    private static final PriceRule GULF_GROUP = rule(PriceScope.GROUP, GULF.toString(), "7", Currency.USD);
    private static final PriceRule EGYPT = rule(PriceScope.COUNTRY, "EG", "199", Currency.EGP);
    private static final List<PriceRule> ALL = List.of(GLOBAL, EUROPE, GULF_GROUP, EGYPT);

    @Test
    void countryRuleWinsOverEverything() {
        Country eg = country("EG", Continent.AF, null);
        PriceResolver.Resolved r = PriceResolver.pick(eg, ALL).orElseThrow();
        assertThat(r.price()).isEqualByComparingTo("199");
        assertThat(r.currency()).isEqualTo(Currency.EGP);
        assertThat(r.scope()).isEqualTo(PriceScope.COUNTRY);
    }

    @Test
    void countryRuleWinsEvenWhenCountryIsInAGroupAndContinentWithRules() {
        Country eg = country("EG", Continent.EU, GULF);
        assertThat(PriceResolver.pick(eg, ALL).orElseThrow().scope()).isEqualTo(PriceScope.COUNTRY);
    }

    @Test
    void groupRuleWinsOverContinentAndGlobal() {
        Country sa = country("SA", Continent.EU, GULF); // continent deliberately has a rule too
        PriceResolver.Resolved r = PriceResolver.pick(sa, ALL).orElseThrow();
        assertThat(r.scope()).isEqualTo(PriceScope.GROUP);
        assertThat(r.price()).isEqualByComparingTo("7");
        assertThat(r.currency()).isEqualTo(Currency.USD);
    }

    @Test
    void continentRuleWinsOverGlobal() {
        Country de = country("DE", Continent.EU, null);
        PriceResolver.Resolved r = PriceResolver.pick(de, ALL).orElseThrow();
        assertThat(r.scope()).isEqualTo(PriceScope.CONTINENT);
        assertThat(r.price()).isEqualByComparingTo("19");
    }

    @Test
    void globalIsTheFallback() {
        Country us = country("US", Continent.NA, null);
        assertThat(PriceResolver.pick(us, ALL).orElseThrow().scope()).isEqualTo(PriceScope.GLOBAL);
    }

    @Test
    void unknownCountryUsesGlobal() {
        assertThat(PriceResolver.pick(null, ALL).orElseThrow().price()).isEqualByComparingTo("15");
    }

    @Test
    void groupWithoutRuleFallsThroughToContinent() {
        Country ma = country("MA", Continent.EU, UUID.randomUUID());
        assertThat(PriceResolver.pick(ma, ALL).orElseThrow().scope()).isEqualTo(PriceScope.CONTINENT);
    }

    @Test
    void noRulesMeansNotPurchasable() {
        assertThat(PriceResolver.pick(country("US", Continent.NA, null), List.of())).isEmpty();
        assertThat(PriceResolver.pick(country("US", Continent.NA, null), List.of(EGYPT))).isEmpty();
    }

    @Test
    void egyptOnlyEverSeesEgyptianPounds() {
        Country eg = country("EG", Continent.AF, GULF);
        // no EGP price for this package: the USD group / continent / global prices are NOT offered in Egypt
        assertThat(PriceResolver.pick(eg, List.of(GLOBAL, GULF_GROUP,
                rule(PriceScope.CONTINENT, "AF", "9", Currency.USD)))).isEmpty();
        assertThat(PriceResolver.pick(eg, ALL).orElseThrow().currency()).isEqualTo(Currency.EGP);
    }

    @Test
    void everyOtherCountryOnlySeesDollars() {
        PriceRule africaInPounds = rule(PriceScope.CONTINENT, "AF", "150", Currency.EGP);
        Country ng = country("NG", Continent.AF, null);
        PriceResolver.Resolved r = PriceResolver.pick(ng, List.of(africaInPounds, GLOBAL)).orElseThrow();
        assertThat(r.currency()).isEqualTo(Currency.USD);
        assertThat(r.scope()).isEqualTo(PriceScope.GLOBAL);
        for (String code : List.of("SA", "AE", "KW", "DE", "US", "MA", "NG")) {
            Country c = country(code, Continent.AS, "SA".equals(code) || "AE".equals(code) || "KW".equals(code) ? GULF : null);
            assertThat(PriceResolver.pick(c, ALL).orElseThrow().currency()).as(code).isEqualTo(Currency.USD);
        }
    }

    private static Country country(String code, Continent continent, UUID groupId) {
        Country c = new Country();
        c.setCode(code);
        c.setNameAr(code);
        c.setNameEn(code);
        c.setContinent(continent);
        c.setDefaultCurrency("EG".equals(code) ? Currency.EGP : Currency.USD);
        c.setGroupId(groupId);
        return c;
    }

    private static PriceRule rule(PriceScope scope, String scopeId, String price, Currency currency) {
        PriceRule r = new PriceRule();
        r.setId(UUID.randomUUID());
        r.setScope(scope);
        r.setScopeId(scopeId);
        r.setPackageId(PKG);
        r.setPrice(new BigDecimal(price));
        r.setCurrency(currency);
        return r;
    }
}
