package com.saadat.dreams.api;

import java.time.Instant;
import java.util.UUID;

/** types.ts AdminTestimonialRow ({@code id} is the testimonial id). */
public record AdminTestimonialRow(UUID id, String userName, UUID dreamId, int rating, String comment, boolean approved,
                                  Instant createdAt) {
}
