package com.saadat.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.saadat.IntegrationTestBase;
import com.saadat.common.api.ApiPaths;
import com.saadat.common.domain.DreamStatus;
import com.saadat.common.domain.LedgerReason;
import com.saadat.common.domain.OrderStatus;
import com.saadat.common.domain.Role;
import com.saadat.credits.service.CreditService;
import com.saadat.dreams.repo.DreamRepository;
import com.saadat.payments.domain.Order;
import com.saadat.payments.provider.WebhookEvent;
import com.saadat.payments.repo.CreditLedgerRepository;
import com.saadat.payments.repo.OrderRepository;
import com.saadat.payments.service.PaymentService;
import com.saadat.users.domain.User;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

class PaymentFlowIntegrationTest extends IntegrationTestBase {

    /** Seeded package p1 (1 credit); SA is in the Gulf group → 49 SAR. */
    private static final String PACKAGE_ONE = "22222222-2222-4222-8222-000000000001";
    private static final String DREAM_TEXT = "رأيت بيتاً قديماً فيه باب مفتوح ينتهي إلى حديقة خضراء مليئة بالورود.";

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    PaymentService paymentService;

    @Autowired
    OrderRepository orderRepository;

    @Autowired
    CreditLedgerRepository ledgerRepository;

    @Autowired
    CreditService creditService;

    @Autowired
    DreamRepository dreamRepository;

    @Test
    void catalogIsPricedForTheUsersCountry() throws Exception {
        User user = createUser("catalog", Role.USER);
        mvc.perform(get(ApiPaths.Public.CATALOG).header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.countryCode").value("SA"))
                .andExpect(jsonPath("$.currency").value("SAR"))
                .andExpect(jsonPath("$.packages[0].id").value(PACKAGE_ONE))
                .andExpect(jsonPath("$.packages[0].price").value(49.0));
    }

    @Test
    void mockCheckoutThenWebhookCreditsAndAutoSubmitsDraft() throws Exception {
        User user = createUser("buyer", Role.USER);
        UUID draft = createDraft(user);

        JsonNode checkout = checkout(user, draft);
        UUID orderId = UUID.fromString(checkout.get("orderId").asText());
        assertThat(checkout.get("provider").asText()).isEqualTo("MOR");
        assertThat(checkout.get("currency").asText()).isEqualTo("SAR");
        assertThat(new BigDecimal(checkout.get("amount").asText())).isEqualByComparingTo("49");
        assertThat(checkout.get("checkoutUrl").asText()).contains("/checkout/mock/" + orderId);
        assertThat(orderRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.INITIATED);

        mvc.perform(post(ApiPaths.Webhooks.ROOT + "/mock/" + orderId).param("success", "true"))
                .andExpect(status().isOk());

        Order paid = orderRepository.findById(orderId).orElseThrow();
        assertThat(paid.getStatus()).isEqualTo(OrderStatus.SUCCESS);
        assertThat(paid.getPaidAt()).isNotNull();
        assertThat(ledgerRepository.existsByOrderIdAndReason(orderId, LedgerReason.PURCHASE)).isTrue();
        // +1 purchased, −1 consumed by the auto-submitted draft
        assertThat(creditService.balance(user.getId())).isZero();
        assertThat(dreamRepository.findById(draft).orElseThrow().getStatus()).isEqualTo(DreamStatus.IN_REVIEW);

        mvc.perform(get(ApiPaths.Me.ORDERS + "/" + orderId).header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.credits").value(1));

        // duplicate webhook → no-op
        int entriesBefore = ledgerRepository.findByUserIdOrderByCreatedAtDesc(user.getId()).size();
        mvc.perform(post(ApiPaths.Webhooks.ROOT + "/mock/" + orderId).param("success", "true"))
                .andExpect(status().isOk());
        assertThat(ledgerRepository.findByUserIdOrderByCreatedAtDesc(user.getId())).hasSize(entriesBefore);
        assertThat(creditService.balance(user.getId())).isZero();
        assertThat(orderRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.SUCCESS);
    }

    @Test
    void duplicateProviderTransactionIsANoOp() throws Exception {
        User user = createUser("dup", Role.USER);
        UUID orderId = UUID.fromString(checkout(user, null).get("orderId").asText());
        Order order = orderRepository.findById(orderId).orElseThrow();
        WebhookEvent event = new WebhookEvent("TXN-" + UUID.randomUUID(), order.getProviderOrderId(), orderId, true,
                order.getAmount(), order.getCurrency().name(), null, null);

        assertThat(paymentService.confirm(event)).isEqualTo(PaymentService.ConfirmOutcome.SUCCESS);
        assertThat(paymentService.confirm(event)).isEqualTo(PaymentService.ConfirmOutcome.DUPLICATE);
        assertThat(creditService.balance(user.getId())).isEqualTo(1);
    }

    @Test
    void amountMismatchMarksSuspiciousWithoutCredits() throws Exception {
        User user = createUser("suspicious", Role.USER);
        UUID orderId = UUID.fromString(checkout(user, null).get("orderId").asText());
        Order order = orderRepository.findById(orderId).orElseThrow();

        WebhookEvent tampered = new WebhookEvent("TXN-" + UUID.randomUUID(), order.getProviderOrderId(), orderId, true,
                order.getAmount().subtract(BigDecimal.ONE), order.getCurrency().name(), null, null);
        assertThat(paymentService.confirm(tampered)).isEqualTo(PaymentService.ConfirmOutcome.SUSPICIOUS);

        assertThat(orderRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.SUSPICIOUS);
        assertThat(ledgerRepository.existsByOrderIdAndReason(orderId, LedgerReason.PURCHASE)).isFalse();
        assertThat(creditService.balance(user.getId())).isZero();

        // a later "correct" webhook cannot turn a SUSPICIOUS order into credits
        WebhookEvent later = new WebhookEvent("TXN-" + UUID.randomUUID(), order.getProviderOrderId(), orderId, true,
                order.getAmount(), order.getCurrency().name(), null, null);
        paymentService.confirm(later);
        assertThat(creditService.balance(user.getId())).isZero();
    }

    @Test
    void cardCountryMismatchMarksSuspicious() throws Exception {
        User user = createUser("country", Role.USER);
        UUID orderId = UUID.fromString(checkout(user, null).get("orderId").asText());
        Order order = orderRepository.findById(orderId).orElseThrow();
        WebhookEvent event = new WebhookEvent("TXN-" + UUID.randomUUID(), order.getProviderOrderId(), orderId, true,
                order.getAmount(), order.getCurrency().name(), "EG", null);

        assertThat(paymentService.confirm(event)).isEqualTo(PaymentService.ConfirmOutcome.SUSPICIOUS);
        assertThat(creditService.balance(user.getId())).isZero();
    }

    @Test
    void failedPaymentGivesNoCredits() throws Exception {
        User user = createUser("failed", Role.USER);
        UUID orderId = UUID.fromString(checkout(user, null).get("orderId").asText());
        mvc.perform(post(ApiPaths.Webhooks.ROOT + "/mock/" + orderId).param("success", "false"))
                .andExpect(status().isOk());
        assertThat(orderRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.FAILED);
        assertThat(creditService.balance(user.getId())).isZero();
    }

    @Test
    void paymobWebhookWithBadHmacIsRejected() throws Exception {
        mvc.perform(post(ApiPaths.Webhooks.PAYMOB).param("hmac", "deadbeef")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"TRANSACTION\",\"obj\":{\"id\":1,\"success\":true}}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void userCannotListAdminOrders() throws Exception {
        User user = createUser("nosy", Role.USER);
        mvc.perform(get(ApiPaths.Admin.ORDERS).header("Authorization", bearer(user)))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ helpers

    private JsonNode checkout(User user, UUID draft) throws Exception {
        Map<String, Object> body = draft == null
                ? Map.of("packageId", PACKAGE_ONE)
                : Map.of("packageId", PACKAGE_ONE, "dreamIds", java.util.List.of(draft.toString()));
        MvcResult r = mvc.perform(post(ApiPaths.Checkout.ROOT).header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(r.getResponse().getContentAsString());
    }

    private UUID createDraft(User user) throws Exception {
        MvcResult r = mvc.perform(post(ApiPaths.Dreams.ROOT).header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("text", DREAM_TEXT, "gender", "MALE"))))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(r.getResponse().getContentAsString()).get("id").asText());
    }
}
