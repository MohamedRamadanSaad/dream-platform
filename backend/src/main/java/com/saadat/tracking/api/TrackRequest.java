package com.saadat.tracking.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * POST /public/track body (contract §1). {@code path} is the SPA route without the query string;
 * {@code sessionId} is the visit id (a cookie that ends after 30 minutes without a page view);
 * {@code visitorId} is the browser id (a long-lived cookie: the same person coming back keeps it; optional because
 * old cached app versions do not send it); {@code referrer} is the full referrer URL (only its host is stored).
 */
public record TrackRequest(
        @NotBlank @Size(max = 255) @Pattern(regexp = "/.*") String path,
        String referrer,
        @NotBlank @Size(max = 100) String sessionId,
        @Size(max = 100) String visitorId) {
}
