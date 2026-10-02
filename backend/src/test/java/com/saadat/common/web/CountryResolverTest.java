package com.saadat.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.saadat.common.domain.CountrySource;
import com.saadat.common.geo.IpCountryLookup;
import com.saadat.common.web.CountryResolver.ResolvedCountry;
import com.saadat.config.props.AppProperties;
import com.saadat.settings.SettingKeys;
import com.saadat.settings.SettingsService;
import java.net.InetAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** Order: CF-IPCountry → X-Country (mock only) → GeoIP of the client address → pricing.default_country. */
class CountryResolverTest {

    private final SettingsService settings = mock(SettingsService.class);
    private final AppProperties properties = new AppProperties();
    private final IpCountryLookup lookup = mock(IpCountryLookup.class);
    private final Map<String, String> database = new HashMap<>();
    private final CountryResolver resolver = new CountryResolver(properties, settings, lookup);

    @BeforeEach
    void setUp() {
        when(settings.getString(SettingKeys.PRICING_DEFAULT_COUNTRY, null)).thenReturn("SA");
        when(lookup.countryCode(any(InetAddress.class)))
                .thenAnswer(call -> Optional.ofNullable(database.get(call.<InetAddress>getArgument(0).getHostAddress())));
        database.put("203.0.113.7", "EG");
    }

    @Test
    void geoIpGivesTheCountryWithoutTheCloudflareHeader() {
        assertThat(resolver.resolve(viaProxy("203.0.113.7"))).isEqualTo(new ResolvedCountry("EG", CountrySource.IP));
    }

    @Test
    void cloudflareHeaderComesFirst() {
        MockHttpServletRequest request = viaProxy("203.0.113.7");
        request.addHeader(CountryResolver.HEADER_CF_COUNTRY, "ae");

        assertThat(resolver.resolve(request)).isEqualTo(new ResolvedCountry("AE", CountrySource.IP));
        verify(lookup, never()).countryCode(any());
    }

    @Test
    void unknownOrPseudoCountriesFallBackToTheDefault() {
        assertThat(resolver.resolve(viaProxy("198.51.100.9"))).isEqualTo(new ResolvedCountry("SA", CountrySource.DEFAULT));
        for (String code : new String[] {"ZZ", "XX", "T1", "E1", "EGY", ""}) {
            database.put("198.51.100.9", code);
            assertThat(resolver.resolve(viaProxy("198.51.100.9"))).as(code)
                    .isEqualTo(new ResolvedCountry("SA", CountrySource.DEFAULT));
        }
        database.put("198.51.100.9", "jo");
        assertThat(resolver.resolve(viaProxy("198.51.100.9"))).isEqualTo(new ResolvedCountry("JO", CountrySource.IP));
    }

    @Test
    void privateAddressesAreNeverLookedUp() {
        assertThat(resolver.resolve(viaProxy("10.0.0.7"))).isEqualTo(new ResolvedCountry("SA", CountrySource.DEFAULT));
        assertThat(resolver.resolve(new MockHttpServletRequest())).isEqualTo(new ResolvedCountry("SA", CountrySource.DEFAULT));
        verify(lookup, never()).countryCode(any());
    }

    @Test
    void mockCountryHeaderBeatsGeoIpOnlyWhenMocksAreAllowed() {
        MockHttpServletRequest request = viaProxy("203.0.113.7");
        request.addHeader(CountryResolver.HEADER_DEV_COUNTRY, "JO");
        assertThat(resolver.resolve(request)).isEqualTo(new ResolvedCountry("EG", CountrySource.IP));

        properties.getAuth().setAllowMock(true);
        assertThat(resolver.resolve(request)).isEqualTo(new ResolvedCountry("JO", CountrySource.HEADER));
    }

    /** A request as Caddy forwards it: peer on the compose network, client in X-Forwarded-For. */
    private static MockHttpServletRequest viaProxy(String clientIp) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("172.18.0.3");
        request.addHeader(ClientIp.HEADER_X_FORWARDED_FOR, clientIp);
        return request;
    }
}
