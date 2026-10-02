package com.saadat.common.geo;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** IP → country database ({@code app.geoip.*}), see {@link GeoIpCountryLookup}. */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.geoip")
public class GeoIpProperties {

    /**
     * Path of the MaxMind-format (MMDB) country database, env {@code GEOIP_DB_PATH} — in production DB-IP's free
     * "IP to Country Lite" file, mounted read-only by docker compose. Empty (or a missing file) = no lookups.
     */
    private String dbPath = "";
}
