package com.saadat.common.geo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.saadat.IntegrationTestBase;
import com.saadat.common.api.ApiPaths;
import com.saadat.common.web.ClientIp;
import com.saadat.common.web.CountryResolver;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Anonymous catalog priced by the GeoIP country through the whole stack (filters included). The test profile points
 * {@code app.geoip.db-path} at a file that does not exist at startup, so every other test runs without GeoIP; this
 * class writes a one-network database there (203.0.113.0/24 → EG) and loads it.
 */
class GeoIpCountryIntegrationTest extends IntegrationTestBase {

    private static final String CLIENT_IN_EGYPT = "203.0.113.7";

    @Autowired
    GeoIpProperties geoIpProperties;

    @Autowired
    GeoIpCountryLookup geoIpLookup;

    @BeforeEach
    void loadTestDatabase() throws IOException {
        if (!geoIpLookup.isLoaded()) {
            Path db = Path.of(geoIpProperties.getDbPath());
            new MmdbTestWriter("DBIP-Country-Lite")
                    .add("203.0.113.0/24", MmdbTestWriter.country("EG", "Egypt", 357_994L, "AF"))
                    .write(db);
            db.toFile().deleteOnExit();
            geoIpLookup.reloadIfChanged();
        }
        assertThat(geoIpLookup.isLoaded()).isTrue();
    }

    @Test
    void anonymousVisitorIsPricedForTheCountryOfTheAddressCaddyForwarded() throws Exception {
        mvc.perform(get(ApiPaths.Public.CATALOG).header(ClientIp.HEADER_X_FORWARDED_FOR, CLIENT_IN_EGYPT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.countryCode").value("EG"))
                .andExpect(jsonPath("$.currency").value("EGP"));
    }

    @Test
    void clientSuppliedForwardingHeadersCannotChooseTheCountry() throws Exception {
        // Spring's ForwardedHeaderFilter would take "Forwarded: for=" or the first X-Forwarded-For entry
        mvc.perform(get(ApiPaths.Public.CATALOG)
                        .header("Forwarded", "for=198.51.100.1")
                        .header(ClientIp.HEADER_X_FORWARDED_FOR, "198.51.100.1, " + CLIENT_IN_EGYPT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.countryCode").value("EG"));
        // the hop Caddy appended is not in the database → the default country
        mvc.perform(get(ApiPaths.Public.CATALOG)
                        .header(ClientIp.HEADER_X_FORWARDED_FOR, CLIENT_IN_EGYPT + ", 192.0.2.1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.countryCode").value("SA"))
                .andExpect(jsonPath("$.currency").value("USD"));
    }

    @Test
    void cloudflareHeaderStillComesFirstAndPrivateAddressesAreNotLookedUp() throws Exception {
        mvc.perform(get(ApiPaths.Public.CATALOG)
                        .header(CountryResolver.HEADER_CF_COUNTRY, "AE")
                        .header(ClientIp.HEADER_X_FORWARDED_FOR, CLIENT_IN_EGYPT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.countryCode").value("AE"));
        mvc.perform(get(ApiPaths.Public.CATALOG).header(ClientIp.HEADER_X_FORWARDED_FOR, "10.0.0.7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.countryCode").value("SA"));
        mvc.perform(get(ApiPaths.Public.CATALOG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.countryCode").value("SA"));
    }
}
