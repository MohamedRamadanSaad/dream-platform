package com.saadat.publicapi.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Public (unauthenticated) response records. */
public final class PublicDtos {

    private PublicDtos() {
    }

    /** types.ts {@code Testimonial}: first name only. */
    public record TestimonialDto(UUID id, String name, int rating, String comment, Instant date) {
    }

    /** {@code GET /public/testimonials} → {@code {items: Testimonial[]}}. */
    public record TestimonialList(List<TestimonialDto> items) {
    }

    /** types.ts {@code PublicStats} (display strings from settings). */
    public record PublicStats(String subscribers, String views, String videos, long interpreted) {
    }

    /** {@code GET /public/push-key}: the VAPID public key (empty when push is not configured). */
    public record PushKey(String publicKey) {
    }
}
