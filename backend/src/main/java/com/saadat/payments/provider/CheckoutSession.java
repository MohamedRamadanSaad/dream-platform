package com.saadat.payments.provider;

/** What a provider returns when a checkout is created: where to send the user and the provider's order ref. */
public record CheckoutSession(String checkoutUrl, String providerOrderId) {
}
