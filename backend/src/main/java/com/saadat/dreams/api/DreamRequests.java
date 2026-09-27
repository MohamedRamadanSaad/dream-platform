package com.saadat.dreams.api;

import com.saadat.common.domain.Gender;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

/** Request/response bodies of the dream routes (types.ts). Length limits come from settings (service). */
public final class DreamRequests {

    private DreamRequests() {
    }

    /** types.ts DreamDraftRequest. */
    public record DraftRequest(@NotBlank String text, @NotNull Gender gender) {
    }

    /** types.ts SubmitDreamsRequest. */
    public record SubmitRequest(@NotEmpty List<@NotNull UUID> dreamIds) {
    }

    /** types.ts SubmitDreamsResponse. */
    public record SubmitResponse(List<UUID> submitted, int remainingCredits) {
    }

    /** {@code {body}} for user replies and interpreter questions. */
    public record MessageRequest(@NotBlank String body) {
    }

    /** types.ts TestimonialRequest. */
    public record TestimonialRequest(@Min(1) @Max(5) int rating, String comment) {
    }

    /** types.ts InterpretationRequest. */
    public record InterpretationRequest(@NotBlank String text) {
    }

    /** {@code {reason}} for POST /admin/dreams/{id}/cancel. */
    public record CancelRequest(String reason) {
    }

    /** {@code {approved}} for PATCH /admin/testimonials/{id}. */
    public record TestimonialPatch(@NotNull Boolean approved) {
    }
}
