package com.saadat.pricing.service;

import com.saadat.common.domain.CountrySource;
import com.saadat.common.security.AuthPrincipal;
import com.saadat.common.web.CountryResolver;
import com.saadat.common.web.CountryResolver.ResolvedCountry;
import com.saadat.users.domain.User;
import com.saadat.users.repo.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The country used for pricing: the authenticated user's stored country (set once at first login, only
 * the interpreter can change it), else the request's country via {@link CountryResolver}.
 */
@Component
@RequiredArgsConstructor
public class CallerCountry {

    private final UserRepository userRepository;
    private final CountryResolver countryResolver;

    public ResolvedCountry resolve(HttpServletRequest request) {
        User user = AuthPrincipal.currentOptional()
                .flatMap(p -> userRepository.findById(p.userId()))
                .orElse(null);
        return resolve(user, request);
    }

    public ResolvedCountry resolve(User user, HttpServletRequest request) {
        if (user != null && user.getCountryCode() != null && !user.getCountryCode().isBlank()) {
            CountrySource source = user.getCountrySource() == null ? CountrySource.IP : user.getCountrySource();
            return new ResolvedCountry(user.getCountryCode().trim(), source);
        }
        return countryResolver.resolve(request);
    }
}
