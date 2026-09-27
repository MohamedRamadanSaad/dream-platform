package com.saadat.users.service;

import com.saadat.common.util.Ages;

import com.saadat.common.domain.AuthProvider;
import com.saadat.common.domain.Locale;
import com.saadat.pricing.domain.Country;
import com.saadat.pricing.repo.CountryRepository;
import com.saadat.users.api.UserDto;
import com.saadat.users.domain.AuthIdentity;
import com.saadat.users.domain.User;
import com.saadat.users.repo.AuthIdentityRepository;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Builds {@link UserDto}: providers from auth_identities, country name (in the user's locale) from countries. */
@Component
public class UserDtoMapper {

    private final AuthIdentityRepository identityRepository;
    private final CountryRepository countryRepository;

    public UserDtoMapper(AuthIdentityRepository identityRepository, CountryRepository countryRepository) {
        this.identityRepository = identityRepository;
        this.countryRepository = countryRepository;
    }

    public UserDto toDto(User user) {
        Set<AuthProvider> providers = EnumSet.noneOf(AuthProvider.class);
        for (AuthIdentity identity : identityRepository.findByUserId(user.getId())) {
            providers.add(identity.getProvider());
        }
        String code = user.getCountryCode() == null ? "" : user.getCountryCode().trim();
        return new UserDto(
                user.getId(),
                user.getName() == null ? "" : user.getName(),
                user.getEmail(),
                user.getGender(),
                user.getBirthDate(),
                Ages.of(user.getBirthDate()),
                user.getRole(),
                List.copyOf(providers),
                user.getLocale(),
                code,
                countryName(code, user.getLocale()),
                user.isOnboarded(),
                user.getCreatedAt());
    }

    public String countryName(String code, Locale locale) {
        if (code == null || code.isBlank()) {
            return "";
        }
        Optional<Country> country = countryRepository.findById(code);
        if (country.isEmpty()) {
            return code;
        }
        return locale == Locale.EN ? country.get().getNameEn() : country.get().getNameAr();
    }
}
