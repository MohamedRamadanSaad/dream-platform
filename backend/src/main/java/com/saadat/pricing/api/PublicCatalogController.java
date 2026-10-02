package com.saadat.pricing.api;

import com.saadat.common.api.ApiPaths;
import com.saadat.common.domain.Locale;
import com.saadat.pricing.service.CallerCountry;
import com.saadat.pricing.service.CatalogService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /public/catalog — prices for the caller's country: the stored user country, else the request's country
 * ({@link com.saadat.common.web.CountryResolver}: CF-IPCountry, GeoIP, default).
 */
@RestController
@RequiredArgsConstructor
public class PublicCatalogController {

    private final CatalogService catalogService;
    private final CallerCountry callerCountry;

    @GetMapping(ApiPaths.Public.CATALOG)
    public CatalogDto catalog(HttpServletRequest request,
                              @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false) String acceptLanguage) {
        String country = callerCountry.resolve(request).countryCode();
        return catalogService.catalog(country, Locale.fromTag(acceptLanguage));
    }
}
