package com.saadat.common.geo;

import java.net.InetAddress;
import java.util.Optional;

/** Country of a public IP address (used by {@link com.saadat.common.web.CountryResolver} without CF-IPCountry). */
public interface IpCountryLookup {

    /** ISO-3166 alpha-2 code as stored in the database; empty when unknown or when no database is loaded. */
    Optional<String> countryCode(InetAddress address);
}
