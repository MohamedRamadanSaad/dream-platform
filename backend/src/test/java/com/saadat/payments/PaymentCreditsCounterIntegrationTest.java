package com.saadat.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.saadat.IntegrationTestBase;
import com.saadat.common.api.ApiPaths;
import com.saadat.common.domain.Role;
import com.saadat.credits.service.CreditService;
import com.saadat.payments.repo.OrderRepository;
import com.saadat.users.domain.User;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * A package of several dreams is one payment with a counter: every dream it pays for carries the same payment number
 * and the interpreter sees "dream 2 of 3, 1 left". With several payments, each dream uses the payment that holds the
 * credit (the oldest first), never simply the latest one.
 */
class PaymentCreditsCounterIntegrationTest extends IntegrationTestBase {

    private static final String PACKAGE_ONE = "22222222-2222-4222-8222-000000000001";
    private static final String PACKAGE_THREE = "22222222-2222-4222-8222-000000000003";
    private static final String DREAM_TEXT = "رأيت أني أصعد جبلاً عالياً وفي قمته بيت أبيض تخرج منه رائحة طيبة.";

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    OrderRepository orderRepository;

    @Autowired
    CreditService creditService;

    @Test
    void oneMultiDreamPaymentCountsDownWithEveryDream() throws Exception {
        User buyer = egyptian("counter-buyer");
        User interpreter = createUser("counter-interpreter", Role.INTERPRETER);
        UUID first = createDraft(buyer);

        UUID orderId = payByInstapay(buyer, interpreter, PACKAGE_THREE, first);
        int credits = orderRepository.findById(orderId).orElseThrow().getCredits();
        assertThat(credits).isGreaterThanOrEqualTo(3);

        JsonNode p1 = payment(interpreter, first);
        assertThat(p1.get("orderId").asText()).isEqualTo(orderId.toString());
        assertThat(p1.get("credits").asInt()).isEqualTo(credits);
        assertThat(p1.get("dreamNumber").asInt()).isEqualTo(1);
        assertThat(p1.get("creditsLeft").asInt()).isEqualTo(credits - 1);

        UUID second = createDraft(buyer);
        submit(buyer, second);
        JsonNode p2 = payment(interpreter, second);
        assertThat(p2.get("orderId").asText()).isEqualTo(orderId.toString());
        assertThat(p2.get("dreamNumber").asInt()).isEqualTo(2);
        assertThat(p2.get("creditsLeft").asInt()).isEqualTo(credits - 2);

        UUID third = createDraft(buyer);
        submit(buyer, third);
        JsonNode p3 = payment(interpreter, third);
        assertThat(p3.get("orderId").asText()).isEqualTo(orderId.toString());
        assertThat(p3.get("dreamNumber").asInt()).isEqualTo(3);
        assertThat(p3.get("creditsLeft").asInt()).isEqualTo(credits - 3);
        // the first dream's card shows the same, current counter
        assertThat(payment(interpreter, first).get("creditsLeft").asInt()).isEqualTo(credits - 3);
        assertThat(creditService.balance(buyer.getId())).isEqualTo(credits - 3);
    }

    @Test
    void eachDreamUsesThePaymentThatHoldsTheCreditNotTheLatestOne() throws Exception {
        User buyer = egyptian("counter-two-orders");
        User interpreter = createUser("counter-two-orders-interpreter", Role.INTERPRETER);

        UUID older = payByInstapay(buyer, interpreter, PACKAGE_ONE, null);
        UUID newer = payByInstapay(buyer, interpreter, PACKAGE_ONE, null);

        UUID a = createDraft(buyer);
        submit(buyer, a);
        UUID b = createDraft(buyer);
        submit(buyer, b);

        JsonNode pa = payment(interpreter, a);
        JsonNode pb = payment(interpreter, b);
        assertThat(pa.get("orderId").asText()).isEqualTo(older.toString());
        assertThat(pa.get("dreamNumber").asInt()).isEqualTo(1);
        assertThat(pa.get("creditsLeft").asInt()).isZero();
        assertThat(pb.get("orderId").asText()).isEqualTo(newer.toString());
        assertThat(pb.get("dreamNumber").asInt()).isEqualTo(1);
        assertThat(pb.get("creditsLeft").asInt()).isZero();
    }

    // ------------------------------------------------------------------ helpers

    private User egyptian(String name) {
        User user = createUser(name, Role.USER);
        user.setCountryCode("EG");
        return userRepository.save(user);
    }

    /** Checkout → transfer details → the interpreter accepts. Returns the order id. */
    private UUID payByInstapay(User buyer, User interpreter, String packageId, UUID draft) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("packageId", packageId);
        if (draft != null) {
            body.put("dreamIds", List.of(draft.toString()));
        }
        UUID orderId = UUID.fromString(json(mvc.perform(post(ApiPaths.Checkout.ROOT)
                        .header("Authorization", bearer(buyer)).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())).get("orderId").asText());
        String reference = String.valueOf(100_000_000_000L + (long) (Math.random() * 899_999_999_999L));
        mvc.perform(post(ApiPaths.Me.ORDERS + "/" + orderId + "/instapay").header("Authorization", bearer(buyer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("reference", reference, "senderName", "BUYER NAME"))))
                .andExpect(status().isOk());
        mvc.perform(post(ApiPaths.Admin.INSTAPAY_TRANSFERS + "/" + orderId + "/approve")
                        .header("Authorization", bearer(interpreter)))
                .andExpect(status().isOk());
        return orderId;
    }

    private void submit(User user, UUID dreamId) throws Exception {
        mvc.perform(post(ApiPaths.Dreams.SUBMIT).header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("dreamIds", List.of(dreamId.toString())))))
                .andExpect(status().isOk());
    }

    private JsonNode payment(User interpreter, UUID dreamId) throws Exception {
        JsonNode detail = json(mvc.perform(get(ApiPaths.Admin.DREAMS + "/" + dreamId)
                .header("Authorization", bearer(interpreter))).andExpect(status().isOk()));
        assertThat(detail.get("payment").isNull()).as("dream %s has a payment", dreamId).isFalse();
        return detail.get("payment");
    }

    private UUID createDraft(User user) throws Exception {
        JsonNode d = json(mvc.perform(post(ApiPaths.Dreams.ROOT).header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("text", DREAM_TEXT, "gender", "MALE"))))
                .andExpect(status().isCreated()));
        return UUID.fromString(d.get("id").asText());
    }

    private JsonNode json(ResultActions actions) throws Exception {
        return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString());
    }
}
