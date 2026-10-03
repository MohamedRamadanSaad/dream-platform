package com.saadat.pricing;

import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saadat.IntegrationTestBase;
import com.saadat.common.api.ApiPaths;
import com.saadat.common.domain.Role;
import com.saadat.users.domain.User;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/** GET /admin/pricing/gaps: the countries that cannot buy an active package, as the prices page warns. */
class PricingGapsIntegrationTest extends IntegrationTestBase {

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void aPackageWithoutPricesIsReportedUntilEveryCountryHasOneInItsCurrency() throws Exception {
        User interpreter = createUser("gaps", Role.INTERPRETER);
        String auth = bearer(interpreter);
        String pkg = objectMapper.readTree(mvc.perform(post(ApiPaths.Admin.PACKAGES)
                        .header(HttpHeaders.AUTHORIZATION, auth).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("nameAr", "باقة الفحص", "nameEn", "Gap check", "descriptionAr", "",
                                "descriptionEn", "", "credits", 4, "sortOrder", 90, "active", true))))
                .andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString()).get("id").asText();
        String egMissing = "$[?(@.countryCode == 'EG')].missing[*].id";
        String deMissing = "$[?(@.countryCode == 'DE')].missing[*].id";

        // no price anywhere: Egypt and Germany both miss it
        mvc.perform(get(ApiPaths.Admin.PRICING_GAPS).header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath(egMissing).value(hasItem(pkg)))
                .andExpect(jsonPath(deMissing).value(hasItem(pkg)));

        // a USD price for all countries: Germany is covered, Egypt still needs a price in pounds
        mvc.perform(post(ApiPaths.Admin.PRICE_RULES).header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("scope", "GLOBAL", "packageId", pkg, "price", 30, "currency", "USD"))))
                .andExpect(status().is2xxSuccessful());
        mvc.perform(get(ApiPaths.Admin.PRICING_GAPS).header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath(egMissing).value(hasItem(pkg)))
                .andExpect(jsonPath("$[?(@.countryCode == 'EG')].currency").value(hasItem("EGP")))
                .andExpect(jsonPath(deMissing).value(not(hasItem(pkg))));

        // the Egyptian price closes the gap
        mvc.perform(post(ApiPaths.Admin.PRICE_RULES).header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("scope", "COUNTRY", "scopeId", "EG", "packageId", pkg, "price", 600,
                                "currency", "EGP"))))
                .andExpect(status().is2xxSuccessful());
        mvc.perform(get(ApiPaths.Admin.PRICING_GAPS).header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath(egMissing).value(not(hasItem(pkg))));

        // a stopped package is never reported
        mvc.perform(put(ApiPaths.Admin.PACKAGE, pkg).header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("active", false))))
                .andExpect(status().isOk());
        mvc.perform(get(ApiPaths.Admin.PRICING_GAPS).header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].missing[?(@.id == '" + pkg + "')]").value(empty()));
    }

    @Test
    void usersCannotReadIt() throws Exception {
        User user = createUser("gaps-user", Role.USER);
        mvc.perform(get(ApiPaths.Admin.PRICING_GAPS).header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isForbidden());
    }

    private String json(Object body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }
}
