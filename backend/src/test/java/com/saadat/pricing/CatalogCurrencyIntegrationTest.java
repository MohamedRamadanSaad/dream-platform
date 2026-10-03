package com.saadat.pricing;

import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saadat.IntegrationTestBase;
import com.saadat.common.api.ApiPaths;
import com.saadat.common.domain.Role;
import com.saadat.common.web.CountryResolver;
import com.saadat.users.domain.User;
import java.util.List;
import java.util.Map;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * Owner's rule (2026-10-03): Egypt sees prices in Egyptian pounds, every other country in US dollars, on the
 * seeded catalog (V3 + V30) and whatever price rules exist.
 */
class CatalogCurrencyIntegrationTest extends IntegrationTestBase {

    private static final String PACKAGE_ONE = "22222222-2222-4222-8222-000000000001";

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void egyptIsPricedInPounds() throws Exception {
        mvc.perform(get(ApiPaths.Public.CATALOG).header(CountryResolver.HEADER_CF_COUNTRY, "EG"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.countryCode").value("EG"))
                .andExpect(jsonPath("$.currency").value("EGP"))
                .andExpect(jsonPath("$.packages.length()").value(greaterThan(0)))
                .andExpect(jsonPath("$.packages[*].currency").value(everyItem(is("EGP"))));
    }

    @Test
    void everyOtherCountryIsPricedInDollars() throws Exception {
        for (String code : List.of("SA", "AE", "KW", "QA", "BH", "OM", "JO", "MA", "NG", "DE", "FR", "GB", "US",
                "TR", "IN")) {
            mvc.perform(get(ApiPaths.Public.CATALOG).header(CountryResolver.HEADER_CF_COUNTRY, code))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.currency").value("USD"))
                    .andExpect(jsonPath("$.packages.length()").value(greaterThan(0)))
                    .andExpect(jsonPath("$.packages[*].currency").value(everyItem(is("USD"))));
        }
    }

    @Test
    void aPriceInTheWrongCurrencyIsRefused() throws Exception {
        User interpreter = createUser("currency", Role.INTERPRETER);
        String auth = bearer(interpreter);
        for (Map<String, Object> body : List.of(
                Map.<String, Object>of("scope", "COUNTRY", "scopeId", "EG", "packageId", PACKAGE_ONE,
                        "price", 9, "currency", "USD"),
                Map.<String, Object>of("scope", "COUNTRY", "scopeId", "DE", "packageId", PACKAGE_ONE,
                        "price", 300, "currency", "EGP"),
                Map.<String, Object>of("scope", "GLOBAL", "packageId", PACKAGE_ONE, "price", 300, "currency", "EGP"),
                Map.<String, Object>of("scope", "CONTINENT", "scopeId", "EU", "packageId", PACKAGE_ONE,
                        "price", 300, "currency", "EGP"))) {
            mvc.perform(post(ApiPaths.Admin.PRICE_RULES).header(HttpHeaders.AUTHORIZATION, auth)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().is4xxClientError())
                    .andExpect(jsonPath("$.code").value(Matchers.anyOf(is("CURRENCY_MISMATCH"))));
        }
    }
}
