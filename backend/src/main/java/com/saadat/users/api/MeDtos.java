package com.saadat.users.api;

import com.saadat.common.domain.Gender;
import com.saadat.common.domain.Locale;
import com.saadat.publicapi.WaitTime;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** /me request/response records (types.ts "Me" section). */
public final class MeDtos {

    private MeDtos() {
    }

    /** All fields optional; only non-null ones are applied. */
    public record PreferencesRequest(
            Locale locale,
            @Size(min = 1, max = 200) @Pattern(regexp = ".*\\S.*", message = "must not be blank") String name,
            Gender gender,
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
}
