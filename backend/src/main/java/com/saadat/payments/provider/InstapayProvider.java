package com.saadat.payments.provider;

import com.saadat.common.domain.PaymentProviderType;
import com.saadat.config.props.AppProperties;
import com.saadat.payments.domain.Order;
import com.saadat.settings.SettingKeys;
import com.saadat.settings.SettingsService;
import com.saadat.users.domain.User;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Manual InstaPay transfer (Egypt, EGP). There is no gateway and no webhook: the "checkout" is the site's own
 * payment page, which shows the amount and the receiving account (settings {@code payments.instapay.*}). The
 * buyer sends the transfer reference ({@code InstapayService.submit}) and the interpreter confirms it
 * ({@code InstapayService.approve} → {@code PaymentService.confirm}).
 */
@Component
@RequiredArgsConstructor
public class InstapayProvider implements PaymentProvider {

    /** SPA route of the payment page; the order id follows. */
    public static final String CHECKOUT_PATH = "/me/checkout/instapay/";
    static final String ORDER_PREFIX = "INSTAPAY-";
    /** Prefix of {@code provider_txn_id}: one InstaPay reference confirms one order. */
    public static final String TXN_PREFIX = "INSTAPAY-REF-";

    private final AppProperties properties;
    private final SettingsService settings;

    /** On, with a receiving address and payment link configured. */
    public boolean isEnabled() {
        return settings.getBool(SettingKeys.INSTAPAY_ENABLED, false)
                && !settings.getString(SettingKeys.INSTAPAY_ADDRESS, "").isBlank()
                && !settings.getString(SettingKeys.INSTAPAY_LINK, "").isBlank();
    }

    @Override
    public PaymentProviderType type() {
        return PaymentProviderType.INSTAPAY;
    }

    @Override
    public CheckoutSession createCheckout(Order order, User user) {
        return new CheckoutSession(properties.getFrontendUrl() + CHECKOUT_PATH + order.getId(),
                ORDER_PREFIX + order.getId());
    }

    /** A bank transfer takes longer than a card form: the order waits {@code payments.instapay.expire_hours}. */
    @Override
    public Optional<Duration> checkoutTtl() {
        return Optional.of(Duration.ofHours(Math.max(1, settings.getInt(SettingKeys.INSTAPAY_EXPIRE_HOURS, 24))));
    }

    /** No webhook exists for InstaPay: every call is refused. */
    @Override
    public boolean verifySignature(HttpServletRequest request, String body) {
        return false;
    }

    @Override
    public WebhookEvent parseWebhook(HttpServletRequest request, String body) {
        throw new UnsupportedOperationException("InstaPay transfers are confirmed by the interpreter");
    }
}
