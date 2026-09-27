package com.saadat.payments.provider;

import com.saadat.common.domain.PaymentProviderType;
import com.saadat.common.error.ForbiddenException;
import com.saadat.config.props.AppProperties;
import com.saadat.payments.domain.Order;
import com.saadat.users.domain.User;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Local/dev provider (only used when {@code app.payments.mock=true}): the SPA shows a fake checkout page at
 * {@code /checkout/mock/{orderId}} which calls {@code POST /webhooks/mock/{orderId}?success=…}.
 */
@Component
@RequiredArgsConstructor
public class MockPaymentProvider implements PaymentProvider {

    static final String CHECKOUT_PATH = "/checkout/mock/";
    static final String ORDER_PREFIX = "MOCK-";
    static final String TXN_OK_SUFFIX = "-OK";
    static final String TXN_FAIL_SUFFIX = "-FAIL-";

    private final AppProperties properties;

    public boolean isEnabled() {
        return properties.getPayments().isMock();
    }

    @Override
    public PaymentProviderType type() {
        return PaymentProviderType.MOCK;
    }

    @Override
    public CheckoutSession createCheckout(Order order, User user) {
        requireEnabled();
        return new CheckoutSession(properties.getFrontendUrl() + CHECKOUT_PATH + order.getId(),
                ORDER_PREFIX + order.getId());
    }

    @Override
    public boolean verifySignature(HttpServletRequest request, String body) {
        return isEnabled();
    }

    @Override
    public WebhookEvent parseWebhook(HttpServletRequest request, String body) {
        throw new UnsupportedOperationException("Use eventFor(order, success)");
    }

    /**
     * The event the mock checkout page triggers. A successful payment has a stable txn id per order (so a
     * repeated success is an idempotent no-op); each failure gets a fresh id.
     */
    public WebhookEvent eventFor(Order order, boolean success) {
        requireEnabled();
        String txn = ORDER_PREFIX + order.getId()
                + (success ? TXN_OK_SUFFIX : TXN_FAIL_SUFFIX + UUID.randomUUID());
        return new WebhookEvent(txn, order.getProviderOrderId(), order.getId(), success, order.getAmount(),
                order.getCurrency().name(), null, null);
    }

    private void requireEnabled() {
        if (!isEnabled()) {
            throw new ForbiddenException("Mock payments are disabled");
        }
    }
}
