package com.saadat.payments.provider;

import com.saadat.common.domain.PaymentProviderType;
import com.saadat.payments.domain.Order;
import com.saadat.users.domain.User;
import jakarta.servlet.http.HttpServletRequest;

/** A payment gateway. Implementations never decide order state; {@code PaymentService.confirm} does. */
public interface PaymentProvider {

    PaymentProviderType type();

    /** Creates the provider-side checkout for a persisted INITIATED order. */
    CheckoutSession createCheckout(Order order, User user);

    /** Verifies the webhook's authenticity (signature/HMAC). */
    boolean verifySignature(HttpServletRequest request, String body);

    /** Parses an already-verified webhook body into a {@link WebhookEvent}. */
    WebhookEvent parseWebhook(HttpServletRequest request, String body);
}
