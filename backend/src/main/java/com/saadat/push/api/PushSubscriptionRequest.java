package com.saadat.push.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** types.ts {@code PushSubscriptionRequest} (the browser's PushSubscription.toJSON() + userAgent). */
public record PushSubscriptionRequest(
        @NotBlank @Size(max = 2048) String endpoint,
        @NotNull @Valid Keys keys,
        @Size(max = 1024) String userAgent) {

    public record Keys(@NotBlank @Size(max = 255) String p256dh, @NotBlank @Size(max = 255) String auth) {
    }
}
