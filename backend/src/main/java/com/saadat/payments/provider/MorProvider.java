package com.saadat.payments.provider;

import com.saadat.common.domain.PaymentProviderType;
import com.saadat.common.error.NotConfiguredException;
import com.saadat.config.props.AppProperties;
import com.saadat.payments.domain.Order;
import com.saadat.users.domain.User;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Merchant-of-record provider for SAR/USD.
 *
 * <p>TODO(mor): the merchant-of-record vendor is not chosen yet. Once {@code app.payments.mor.*}
 * (MOR_API_KEY, MOR_STORE_ID, MOR_WEBHOOK_SECRET, MOR_BASE_URL) are provided, implement:
 * <ol>
 *   <li>{@link #createCheckout}: create a hosted checkout with our order id as the external reference,
 *       amount/currency from the order, success/cancel URLs under {@code app.frontend-url}.</li>
 *   <li>{@link #verifySignature}: vendor webhook signature (usually HMAC-SHA256 of the raw body with
 *       MOR_WEBHOOK_SECRET, sent in a header).</li>
 *   <li>{@link #parseWebhook}: map to {@link WebhookEvent} (txn id, our order id, amount, currency,
 *       card country when available).</li>
 * </ol>
 * Until then every call throws {@link NotConfiguredException} (503), and POST /webhooks/mor answers 501.
 */
@Component
@RequiredArgsConstructor
public class MorProvider implements PaymentProvider {

    private final AppProperties properties;

    public boolean isConfigured() {
        return properties.getPayments().getMor().isConfigured();
    }

    @Override
    public PaymentProviderType type() {
        return PaymentProviderType.MOR;
    }

    @Override
    public CheckoutSession createCheckout(Order order, User user) {
        throw new NotConfiguredException("Merchant-of-record payments are not configured yet");
    }

    @Override
    public boolean verifySignature(HttpServletRequest request, String body) {
        throw new NotConfiguredException("Merchant-of-record payments are not configured yet");
    }

    @Override
    public WebhookEvent parseWebhook(HttpServletRequest request, String body) {
        throw new NotConfiguredException("Merchant-of-record payments are not configured yet");
    }
}
