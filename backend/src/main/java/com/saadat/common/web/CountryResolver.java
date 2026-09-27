package com.saadat.common.web;

import com.saadat.common.domain.CountrySource;
import com.saadat.config.props.AppProperties;
import com.saadat.settings.SettingKeys;
import com.saadat.settings.SettingsService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Resolves the caller's ISO-3166 alpha-2 country (contract rule 1: the client never sends a country):
 * <ol>
 *   <li>{@code CF-IPCountry} (Cloudflare) → source IP</li>
 *   <li>{@code X-Country} — only when {@code app.auth.allow-mock=true} (local/test) → source HEADER</li>
 *   <li>setting {@code pricing.default_country} → source DEFAULT</li>
 * </ol>
 */
@Component
public class CountryResolver {

    public static final String HEADER_CF_COUNTRY = "CF-IPCountry";
    public static final String HEADER_DEV_COUNTRY = "X-Country";

    private static final Pattern ISO_ALPHA2 = Pattern.compile("^[A-Z]{2}$");
    /** Cloudflare pseudo-codes: XX = unknown, T1 = Tor. */
    private static final Set<String> CF_PSEUDO = Set.of("XX", "T1");

    private final AppProperties properties;
    private final SettingsService settings;

    public CountryResolver(AppProperties properties, SettingsService settings) {
        this.properties = properties;
        this.settings = settings;
    }

    public ResolvedCountry resolve(HttpServletRequest request) {
        String cf = normalize(request.getHeader(HEADER_CF_COUNTRY));
        if (cf != null) {
            return new ResolvedCountry(cf, CountrySource.IP);
        }
        if (properties.getAuth().isAllowMock()) {
            String dev = normalize(request.getHeader(HEADER_DEV_COUNTRY));
            if (dev != null) {
                return new ResolvedCountry(dev, CountrySource.HEADER);
            }
        }
        return new ResolvedCountry(defaultCountry(), CountrySource.DEFAULT);
    }

    public String resolveCode(HttpServletRequest request) {
        return resolve(request).countryCode();
    }

    public String defaultCountry() {
        String fallback = normalize(settings.getString(SettingKeys.PRICING_DEFAULT_COUNTRY, null));
        if (fallback == null) {
            throw new IllegalStateException("Setting " + SettingKeys.PRICING_DEFAULT_COUNTRY + " is missing");
        }
        return fallback;
    }

    /** Upper-cases and validates; returns null for missing, malformed or Cloudflare pseudo codes. */
    static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String code = raw.trim().toUpperCase(Locale.ROOT);
        if (!ISO_ALPHA2.matcher(code).matches() || CF_PSEUDO.contains(code)) {
            return null;
        }
        return code;
    }

    public record ResolvedCountry(String countryCode, CountrySource source) {
    }
}
