package com.saadat.payments.provider;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A verified payment notification, provider-independent.
 *
 * @param providerTxnId   the provider's transaction id (unique → idempotency key)
 * @param providerOrderId the provider's order id (fallback lookup)
 * @param orderId         our order id when the provider echoes it (merchant_order_id), else null
 * @param success         whether the provider reports the payment as successful
 * @param amount          amount in major units (e.g. 49.00)
 * @param currency        ISO currency code as sent by the provider
 * @param cardCountry     card-issuer country (ISO alpha-2) when the provider exposes it, else null
 * @param raw             raw payload (for logs/audit; never trusted beyond the verified fields)
 */
public record WebhookEvent(
        String providerTxnId,
        String providerOrderId,
        UUID orderId,
        boolean success,
        BigDecimal amount,
        String currency,
        String cardCountry,
        String raw) {
}
