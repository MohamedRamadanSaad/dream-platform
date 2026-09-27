package com.saadat.payments.provider;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.saadat.common.domain.PaymentProviderType;
import com.saadat.common.error.ApiException;
import com.saadat.common.error.NotConfiguredException;
import com.saadat.config.props.AppProperties;
import com.saadat.payments.domain.Order;
import com.saadat.settings.SettingKeys;
import com.saadat.settings.SettingsService;
import com.saadat.users.domain.User;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Paymob Accept (EGP). Checkout: auth token → ecommerce order (merchant_order_id = our order id) →
 * payment key → iframe URL. Webhook: "transaction processed" JSON callback, HMAC in query param {@code hmac}
 * (see {@link PaymobHmac}).
 */
@Slf4j
@Component
public class PaymobProvider implements PaymentProvider {

    public static final String HMAC_PARAM = "hmac";

    static final String PATH_AUTH = "/api/auth/tokens";
    static final String PATH_ORDERS = "/api/ecommerce/orders";
    static final String PATH_PAYMENT_KEYS = "/api/acceptance/payment_keys";
    static final String PATH_IFRAME = "/api/acceptance/iframes/{iframeId}?payment_token={token}";

    /** Paymob requires every billing_data field; unknown ones must be "NA" (protocol filler, not business data). */
    private static final String NA = "NA";
    private static final int SECONDS_PER_MINUTE = 60;

    private final AppProperties properties;
    private final SettingsService settingsService;
    private final RestClient.Builder restClientBuilder;
    private final ObjectMapper objectMapper;

    public PaymobProvider(AppProperties properties, SettingsService settingsService,
                          RestClient.Builder restClientBuilder, ObjectMapper objectMapper) {
        this.properties = properties;
        this.settingsService = settingsService;
        this.restClientBuilder = restClientBuilder;
        this.objectMapper = objectMapper;
    }

    @Override
    public PaymentProviderType type() {
        return PaymentProviderType.PAYMOB;
    }

    @Override
    public CheckoutSession createCheckout(Order order, User user) {
        AppProperties.Paymob cfg = properties.getPayments().getPaymob();
        if (!cfg.isConfigured()) {
            throw new NotConfiguredException("Paymob is not configured");
        }
        RestClient client = restClientBuilder.clone().baseUrl(cfg.getBaseUrl()).build();
        long amountCents = toCents(order.getAmount());
        String currency = order.getCurrency().name();
        try {
            JsonNode auth = client.post().uri(PATH_AUTH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("api_key", cfg.getApiKey()))
                    .retrieve().body(JsonNode.class);
            String token = requireText(auth, "token");

            Map<String, Object> orderBody = new LinkedHashMap<>();
            orderBody.put("auth_token", token);
            orderBody.put("delivery_needed", false);
            orderBody.put("amount_cents", amountCents);
            orderBody.put("currency", currency);
            orderBody.put("merchant_order_id", order.getId().toString());
            orderBody.put("items", List.of());
            JsonNode paymobOrder = client.post().uri(PATH_ORDERS)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(orderBody)
                    .retrieve().body(JsonNode.class);
            String paymobOrderId = requireText(paymobOrder, "id");

            Map<String, Object> keyBody = new LinkedHashMap<>();
            keyBody.put("auth_token", token);
            keyBody.put("amount_cents", amountCents);
            keyBody.put("expiration", settingsService.getInt(SettingKeys.ORDERS_EXPIRE_MINUTES) * SECONDS_PER_MINUTE);
            keyBody.put("order_id", paymobOrderId);
            keyBody.put("billing_data", billingData(user));
            keyBody.put("currency", currency);
            keyBody.put("integration_id", Long.parseLong(cfg.getIntegrationId().trim()));
            keyBody.put("lock_order_when_paid", true);
            JsonNode key = client.post().uri(PATH_PAYMENT_KEYS)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(keyBody)
                    .retrieve().body(JsonNode.class);
            String paymentToken = requireText(key, "token");

            String url = cfg.getBaseUrl() + PATH_IFRAME
                    .replace("{iframeId}", cfg.getIframeId().trim())
                    .replace("{token}", paymentToken);
            return new CheckoutSession(url, paymobOrderId);
        } catch (RestClientException e) {
            log.warn("Paymob checkout failed for order {}: {}", order.getId(), e.getMessage());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "payment-provider-error", "PAYMENT_PROVIDER_ERROR",
                    "Payment provider is unavailable");
        }
    }

    @Override
    public boolean verifySignature(HttpServletRequest request, String body) {
        String secret = properties.getPayments().getPaymob().getHmacSecret();
        JsonNode obj = readObj(body);
        return PaymobHmac.matches(obj, secret, request.getParameter(HMAC_PARAM));
    }

    @Override
    public WebhookEvent parseWebhook(HttpServletRequest request, String body) {
        JsonNode obj = readObj(body);
        if (obj == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "malformed-request", null, "Missing obj");
        }
        String txnId = PaymobHmac.text(obj, "id");
        String paymobOrderId = PaymobHmac.text(obj, "order.id");
        UUID merchantOrderId = parseUuid(PaymobHmac.text(obj, "order.merchant_order_id"));
        boolean success = obj.path("success").asBoolean(false)
                && !obj.path("pending").asBoolean(false)
                && !obj.path("is_voided").asBoolean(false)
                && !obj.path("is_refunded").asBoolean(false);
        BigDecimal amount = new BigDecimal(obj.path("amount_cents").asText("0"))
                .movePointLeft(2);
        String currency = PaymobHmac.text(obj, "currency");
        // TODO(paymob): Paymob does not document a stable card-issuer-country field on the callback; when one
        // is confirmed (e.g. from the acquirer data), map it here so the country-mismatch check applies.
        String cardCountry = null;
        return new WebhookEvent(emptyToNull(txnId), emptyToNull(paymobOrderId), merchantOrderId, success, amount,
                currency, cardCountry, body);
    }

    private JsonNode readObj(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode obj = root.get("obj");
            return obj == null || obj.isNull() ? null : obj;
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static Map<String, Object> billingData(User user) {
        String name = user.getName() == null || user.getName().isBlank() ? NA : user.getName().trim();
        int space = name.indexOf(' ');
        String first = space > 0 ? name.substring(0, space) : name;
        String last = space > 0 ? name.substring(space + 1).trim() : NA;
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("first_name", first);
        b.put("last_name", last.isEmpty() ? NA : last);
        b.put("email", user.getEmail());
        b.put("phone_number", NA);
        b.put("apartment", NA);
        b.put("floor", NA);
        b.put("street", NA);
        b.put("building", NA);
        b.put("shipping_method", NA);
        b.put("postal_code", NA);
        b.put("city", NA);
        b.put("country", user.getCountryCode() == null ? NA : user.getCountryCode());
        b.put("state", NA);
        return b;
    }

    static long toCents(BigDecimal amount) {
        return amount.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    private static String requireText(JsonNode node, String field) {
        String v = node == null ? "" : node.path(field).asText("");
        if (v.isEmpty()) {
            throw new RestClientException("Paymob response without '" + field + "'");
        }
        return v;
    }

    private static UUID parseUuid(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(s.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }
}
