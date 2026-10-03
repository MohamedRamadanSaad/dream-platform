package com.saadat.payments.provider;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.saadat.common.api.ApiPaths;
import com.saadat.common.domain.Locale;
import com.saadat.common.domain.PaymentProviderType;
import com.saadat.common.error.ApiException;
import com.saadat.common.error.NotConfiguredException;
import com.saadat.config.props.AppProperties;
import com.saadat.payments.domain.Order;
import com.saadat.users.domain.User;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Kashier (Egypt, EGP). Checkout: one server call creates a payment session
 * ({@code POST /v3/payment/sessions}, Secret Key in {@code Authorization}, Payment API Key in {@code api-key}); the
 * user is sent to its {@code sessionUrl}. Our order id travels as {@code order} and comes back as
 * {@code merchantOrderId}. Webhook: JSON {@code {event, data}} signed in header {@code x-kashier-signature}
 * (see {@link KashierSignature}); only {@code pay} + {@code SUCCESS} confirms an order.
 */
@Slf4j
@Component
public class KashierProvider implements PaymentProvider {

    static final String PATH_SESSIONS = "/v3/payment/sessions";
    /** Where Kashier sends the browser back; the payments page shows the order once the webhook confirms it. */
    static final String RETURN_PATH = "/me/payments";
    static final String EVENT_PAY = "pay";
    static final String STATUS_SUCCESS = "SUCCESS";
    static final String STATUS_FAILURE = "FAILURE";
    /** Card tries allowed inside one session before Kashier closes it (the order then expires on our side). */
    static final int MAX_FAILURE_ATTEMPTS = 3;

    private final AppProperties properties;
    private final RestClient.Builder restClientBuilder;
    private final ObjectMapper objectMapper;

    public KashierProvider(AppProperties properties, RestClient.Builder restClientBuilder, ObjectMapper objectMapper) {
        this.properties = properties;
        this.restClientBuilder = restClientBuilder;
        this.objectMapper = objectMapper;
    }

    @Override
    public PaymentProviderType type() {
        return PaymentProviderType.KASHIER;
    }

    @Override
    public CheckoutSession createCheckout(Order order, User user) {
        AppProperties.Kashier cfg = properties.getPayments().getKashier();
        if (!cfg.isConfigured()) {
            throw new NotConfiguredException("Kashier is not configured");
        }
        Map<String, Object> customer = new LinkedHashMap<>();
        customer.put("email", user.getEmail());
        customer.put("reference", user.getId().toString());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("merchantId", cfg.getMerchantId().trim());
        body.put("order", order.getId().toString());
        body.put("amount", formatAmount(order.getAmount()));
        body.put("currency", order.getCurrency().name());
        body.put("expireAt", order.getExpiresAt().toString());
        body.put("maxFailureAttempts", MAX_FAILURE_ATTEMPTS);
        body.put("paymentType", "credit");
        body.put("type", "one-time");
        body.put("allowedMethods", "card,wallet");
        body.put("defaultMethod", "card");
        body.put("display", user.getLocale() == Locale.EN ? "en" : "ar");
        body.put("description", order.getPackageNameSnapshot());
        body.put("merchantRedirect", properties.getFrontendUrl() + RETURN_PATH);
        body.put("failureRedirect", true);
        body.put("serverWebhook", properties.getApiUrl() + ApiPaths.Webhooks.KASHIER);
        body.put("customer", customer);
        body.put("interactionSource", "ECOMMERCE");
        body.put("enable3DS", true);
        body.put("manualCapture", false);

        try {
            JsonNode session = restClientBuilder.clone().baseUrl(cfg.resolvedBaseUrl()).build()
                    .post().uri(PATH_SESSIONS)
                    .header("Authorization", cfg.getSecretKey().trim())
                    .header("api-key", cfg.getApiKey().trim())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve().body(JsonNode.class);
            String url = text(session, "sessionUrl");
            String id = text(session, "_id");
            if (url.isEmpty()) {
                throw new RestClientException("Kashier response without 'sessionUrl'");
            }
            return new CheckoutSession(url, id.isEmpty() ? null : id);
        } catch (RestClientException e) {
            log.warn("Kashier checkout failed for order {}: {}", order.getId(), e.getMessage());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "payment-provider-error", "PAYMENT_PROVIDER_ERROR",
                    "Payment provider is unavailable");
        }
    }

    @Override
    public boolean verifySignature(HttpServletRequest request, String body) {
        return KashierSignature.matches(readData(body), properties.getPayments().getKashier().getApiKey(),
                request.getHeader(KashierSignature.HEADER));
    }

    /**
     * Whether a verified webhook should reach {@code PaymentService.confirm}: only a {@code pay} event that
     * succeeded. Failed card tries are ignored because the customer can retry inside the same session; an order
     * that is never paid simply expires. Refunds, voids and pending states are handled in the dashboard.
     */
    public boolean isPaymentResult(String body) {
        JsonNode root = readRoot(body);
        JsonNode data = root == null ? null : root.get("data");
        return root != null && data != null
                && EVENT_PAY.equalsIgnoreCase(root.path("event").asText(""))
                && STATUS_SUCCESS.equalsIgnoreCase(data.path("status").asText(""));
    }

    /** Short description of a webhook for logs (event/status/transaction). */
    public String describe(String body) {
        JsonNode root = readRoot(body);
        if (root == null) {
            return "unreadable";
        }
        JsonNode data = root.path("data");
        return root.path("event").asText("?") + "/" + data.path("status").asText("?") + " txn="
                + data.path("transactionId").asText("?");
    }

    @Override
    public WebhookEvent parseWebhook(HttpServletRequest request, String body) {
        JsonNode data = readData(body);
        if (data == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "malformed-request", null, "Missing data");
        }
        String status = text(data, "status");
        boolean success = STATUS_SUCCESS.equalsIgnoreCase(status);
        BigDecimal amount;
        try {
            amount = new BigDecimal(data.path("amount").asText("0"));
        } catch (NumberFormatException e) {
            amount = null;
        }
        // Kashier does not send the card-issuer country, so the country-mismatch check does not apply here.
        return new WebhookEvent(emptyToNull(text(data, "transactionId")), emptyToNull(text(data, "kashierOrderId")),
                parseUuid(text(data, "merchantOrderId")), success, amount, emptyToNull(text(data, "currency")),
                null, body);
    }

    private JsonNode readRoot(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            return root == null || !root.isObject() ? null : root;
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private JsonNode readData(String body) {
        JsonNode root = readRoot(body);
        JsonNode data = root == null ? null : root.get("data");
        return data == null || !data.isObject() ? null : data;
    }

    static String formatAmount(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return "";
        }
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? "" : v.asText("");
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
