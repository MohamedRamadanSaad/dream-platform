package com.saadat.users.api;

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
        Role role,
        List<AuthProvider> providers,
        Locale locale,
        String countryCode,
        String countryName,
        boolean onboarded,
        Instant createdAt) {
}
