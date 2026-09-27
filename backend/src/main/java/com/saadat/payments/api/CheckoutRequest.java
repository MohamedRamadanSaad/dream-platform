package com.saadat.payments.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/** types.ts CheckoutRequest. The client never sends a price or a country. */
public record CheckoutRequest(@NotNull UUID packageId, @Size(max = 64) String couponCode,
                              @Size(max = 50) List<UUID> dreamIds) {
}
