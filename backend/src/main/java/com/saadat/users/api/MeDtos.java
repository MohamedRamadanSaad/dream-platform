package com.saadat.users.api;

import jakarta.validation.constraints.Past;

import java.time.Instant;
import java.time.LocalDate;

import com.saadat.common.domain.Device;
import com.saadat.common.domain.Gender;
import com.saadat.common.domain.Locale;
import com.saadat.publicapi.WaitTime;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** /me request/response records (types.ts "Me" section). */
public final class MeDtos {

    private MeDtos() {
    }

    /** All fields optional; only non-null ones are applied. */
    public record PreferencesRequest(
            Locale locale,
            @Size(min = 1, max = 200) @Pattern(regexp = ".*\\S.*", message = "must not be blank") String name,
            Gender gender,
            @Past LocalDate birthDate,
            Boolean marketingOptIn) {
    }

    /** types.ts {@code DashboardSummary}. */
    public record DashboardSummary(
            long drafts,
            long inReview,
            long awaitingReply,
            long interpreted,
            int credits,
            long unreadNotifications,
            WaitTime waitTime) {
    }

    /**
     * types.ts {@code DeviceDto} (docs/SESSIONS_PROFILE_CONTRACT.md §2): one active refresh-token family of the caller.
     * {@code id} = family id; {@code browser}/{@code os}/{@code deviceType} parsed from the stored User-Agent;
     * {@code countryName} localized by Accept-Language (null when the country is unknown); {@code signedInAt} = first
     * token of the family, {@code lastActiveAt} = newest token; {@code current} = the family of the calling access
     * token ({@code sid}); {@code persistent} = signed in with "remember me".
     */
    public record DeviceDto(
            UUID id,
            String browser,
            String os,
            Device deviceType,
            String countryCode,
            String countryName,
            Instant signedInAt,
            Instant lastActiveAt,
            boolean current,
            boolean persistent) {
    }
}
