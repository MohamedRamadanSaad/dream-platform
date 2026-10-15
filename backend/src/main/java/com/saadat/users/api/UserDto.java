package com.saadat.users.api;

import java.time.LocalDate;

import com.saadat.common.domain.AuthProvider;
import com.saadat.common.domain.Gender;
import com.saadat.common.domain.Locale;
import com.saadat.common.domain.Role;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** types.ts {@code UserDto}. {@code locale} serializes as "ar"/"en". */
public record UserDto(
        UUID id,
        String name,
        String email,
        Gender gender,
        LocalDate birthDate,
        Integer age,
        Role role,
        List<AuthProvider> providers,
        Locale locale,
        String countryCode,
        String countryName,
        boolean onboarded,
        Instant createdAt,
        /** New-video e-mails on (users.marketing_opt_in). */
        boolean marketingOptIn,
        /** The account has a password (V37). */
        boolean hasPassword,
        /** The terms were accepted (users.terms_accepted_at not null): onboarding hides its terms checkbox. */
        boolean termsAccepted) {
}
