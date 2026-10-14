package com.saadat.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.saadat.IntegrationTestBase;
import com.saadat.common.api.ApiPaths;
import com.saadat.common.domain.DreamStatus;
import com.saadat.common.domain.LedgerReason;
import com.saadat.common.domain.OrderStatus;
import com.saadat.common.domain.PaymentProviderType;
import com.saadat.common.domain.Role;
import com.saadat.credits.service.CreditService;
import com.saadat.dreams.repo.DreamRepository;
import com.saadat.payments.domain.Order;
import com.saadat.payments.repo.CreditLedgerRepository;
import com.saadat.payments.repo.OrderRepository;
import com.saadat.settings.SettingKeys;
import com.saadat.settings.SettingsService;
import com.saadat.users.domain.User;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Egyptian buyers pay by manual InstaPay transfer (V34): checkout opens the site's payment page, the buyer sends the
 * transfer reference (and may attach a dream), and credits are added only when the interpreter accepts.
 */
class InstapayFlowIntegrationTest extends IntegrationTestBase {

    /** Seeded package p1 (1 credit). */
    private static final String PACKAGE_ONE = "22222222-2222-4222-8222-000000000001";
    private static final String DREAM_TEXT = "رأيت أني أمشي في طريق طويل بين نخيل كثير حتى وصلت إلى نهر صافٍ.";

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    OrderRepository orderRepository;

    @Autowired
    CreditLedgerRepository ledgerRepository;

    @Autowired
    CreditService creditService;

    @Autowired
    DreamRepository dreamRepository;

    @Autowired
    SettingsService settingsService;

    @Test
    void egyptianCheckoutWaitsForTheInterpreterThenCreditsAndSendsTheDream() throws Exception {
        User buyer = egyptian("insta-buyer");
        User interpreter = createUser("insta-interpreter", Role.INTERPRETER);
        UUID draft = createDraft(buyer);

        JsonNode checkout = checkout(buyer, draft);
        UUID orderId = UUID.fromString(checkout.get("orderId").asText());
        assertThat(checkout.get("provider").asText()).isEqualTo("INSTAPAY");
        assertThat(checkout.get("currency").asText()).isEqualTo("EGP");
        assertThat(checkout.get("checkoutUrl").asText()).endsWith("/me/checkout/instapay/" + orderId);
        Order created = orderRepository.findById(orderId).orElseThrow();
        assertThat(created.getProvider()).isEqualTo(PaymentProviderType.INSTAPAY);
        // a transfer gets hours, not the card form's minutes
        assertThat(Duration.between(created.getCreatedAt(), created.getExpiresAt())).isGreaterThan(Duration.ofHours(2));

        mvc.perform(get(ApiPaths.Me.ORDERS + "/" + orderId + "/instapay").header("Authorization", bearer(buyer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INITIATED"))
                .andExpect(jsonPath("$.address").value(settingsService.getString(SettingKeys.INSTAPAY_ADDRESS)))
                .andExpect(jsonPath("$.link").value(settingsService.getString(SettingKeys.INSTAPAY_LINK)))
                .andExpect(jsonPath("$.attachedDreams").value(1));

        // Arabic digits and spaces are accepted and stored as plain digits
        submit(buyer, orderId, "٢٠٢٣ ٤٧٤٠ ٣٧٩٣", "MOHAMED ALI", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.reference").value("202347403793"));
        assertThat(creditService.balance(buyer.getId())).isZero();
        assertThat(dreamRepository.findById(draft).orElseThrow().getStatus()).isEqualTo(DreamStatus.DRAFT);

        mvc.perform(get(ApiPaths.Admin.INSTAPAY_TRANSFER_COUNTS).header("Authorization", bearer(interpreter)))
                .andExpect(status().isOk());
        JsonNode list = json(mvc.perform(get(ApiPaths.Admin.INSTAPAY_TRANSFERS)
                .header("Authorization", bearer(interpreter))).andExpect(status().isOk()));
        assertThat(list.get("pending").findValuesAsText("orderId")).contains(orderId.toString());

        mvc.perform(approve(orderId).header("Authorization", bearer(interpreter)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
        Order paid = orderRepository.findById(orderId).orElseThrow();
        assertThat(paid.getStatus()).isEqualTo(OrderStatus.SUCCESS);
        assertThat(paid.getReviewedBy()).isEqualTo(interpreter.getId());
        assertThat(ledgerRepository.existsByOrderIdAndReason(orderId, LedgerReason.PURCHASE)).isTrue();
        // +1 bought, −1 used by the dream sent with the transfer
        assertThat(creditService.balance(buyer.getId())).isZero();
        assertThat(dreamRepository.findById(draft).orElseThrow().getStatus()).isEqualTo(DreamStatus.IN_REVIEW);

        // a second click is refused and changes nothing
        mvc.perform(approve(orderId).header("Authorization", bearer(interpreter)))
                .andExpect(status().isConflict());
        assertThat(creditService.balance(buyer.getId())).isZero();
    }

    @Test
    void aDreamWrittenOnThePaymentPageIsAttached() throws Exception {
        User buyer = egyptian("insta-dream");
        User interpreter = createUser("insta-dream-interpreter", Role.INTERPRETER);
        UUID orderId = UUID.fromString(checkout(buyer, null).get("orderId").asText());
        UUID draft = createDraft(buyer);

        submit(buyer, orderId, uniqueReference(), "SARA HASSAN", List.of(draft))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attachedDreams").value(1));
        mvc.perform(approve(orderId).header("Authorization", bearer(interpreter)))
                .andExpect(status().isOk());
        assertThat(dreamRepository.findById(draft).orElseThrow().getStatus()).isEqualTo(DreamStatus.IN_REVIEW);

        // someone else's dream cannot be attached
        User other = egyptian("insta-dream-other");
        UUID otherOrder = UUID.fromString(checkout(other, null).get("orderId").asText());
        submit(other, otherOrder, uniqueReference(), "OTHER NAME", List.of(createDraft(buyer)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_DREAMS"));
    }

    @Test
    void oneReferencePaysOneOrderOnly() throws Exception {
        String reference = uniqueReference();
        User first = egyptian("insta-dup-1");
        UUID firstOrder = UUID.fromString(checkout(first, null).get("orderId").asText());
        submit(first, firstOrder, reference, "FIRST BUYER", null).andExpect(status().isOk());

        User second = egyptian("insta-dup-2");
        UUID secondOrder = UUID.fromString(checkout(second, null).get("orderId").asText());
        submit(second, secondOrder, reference, "SECOND BUYER", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_REFERENCE"));

        submit(second, secondOrder, "12ab", "SECOND BUYER", null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_REFERENCE"));
    }

    @Test
    void rejectionTellsTheBuyerWhoMayCorrectTheReference() throws Exception {
        User buyer = egyptian("insta-reject");
        User interpreter = createUser("insta-reject-interpreter", Role.INTERPRETER);
        UUID orderId = UUID.fromString(checkout(buyer, null).get("orderId").asText());
        submit(buyer, orderId, uniqueReference(), "WRONG TYPED", null).andExpect(status().isOk());

        mvc.perform(post(ApiPaths.Admin.INSTAPAY_TRANSFERS + "/" + orderId + "/reject")
                        .header("Authorization", bearer(interpreter))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"No transfer with this number\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"));
        assertThat(creditService.balance(buyer.getId())).isZero();
        mvc.perform(get(ApiPaths.Me.ORDERS + "/" + orderId + "/instapay").header("Authorization", bearer(buyer)))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.reviewNote").value("No transfer with this number"));

        submit(buyer, orderId, uniqueReference(), "RIGHT NAME", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.reviewNote").isEmpty());
        mvc.perform(approve(orderId).header("Authorization", bearer(interpreter)))
                .andExpect(status().isOk());
        assertThat(creditService.balance(buyer.getId())).isEqualTo(1);
    }

    @Test
    void aReferenceChangedAfterTheInterpreterLookedIsNotApproved() throws Exception {
        User buyer = egyptian("insta-swap");
        User interpreter = createUser("insta-swap-interpreter", Role.INTERPRETER);
        UUID orderId = UUID.fromString(checkout(buyer, null).get("orderId").asText());
        String seen = uniqueReference();
        submit(buyer, orderId, seen, "REAL SENDER", null).andExpect(status().isOk());
        // the buyer swaps the reference while the interpreter is checking the old one
        submit(buyer, orderId, uniqueReference(), "REAL SENDER", null).andExpect(status().isOk());

        mvc.perform(post(approvePath(orderId)).header("Authorization", bearer(interpreter))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reference\":\"" + seen + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REFERENCE_CHANGED"));
        assertThat(creditService.balance(buyer.getId())).isZero();
        assertThat(orderRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.PENDING_REVIEW);
    }

    @Test
    void onlyTheOwnerAndTheInterpreterReachAnOrder() throws Exception {
        User buyer = egyptian("insta-owner");
        UUID orderId = UUID.fromString(checkout(buyer, null).get("orderId").asText());
        User stranger = egyptian("insta-stranger");

        mvc.perform(get(ApiPaths.Me.ORDERS + "/" + orderId + "/instapay").header("Authorization", bearer(stranger)))
                .andExpect(status().isNotFound());
        submit(buyer, orderId, uniqueReference(), "OWNER NAME", null).andExpect(status().isOk());
        mvc.perform(approve(orderId).header("Authorization", bearer(buyer)))
                .andExpect(status().isForbidden());
        mvc.perform(get(ApiPaths.Admin.INSTAPAY_SETTINGS).header("Authorization", bearer(buyer)))
                .andExpect(status().isForbidden());
        assertThat(creditService.balance(buyer.getId())).isZero();
    }

    @Test
    void anExpiredOrderCannotBeSubmitted() throws Exception {
        User buyer = egyptian("insta-expired");
        UUID orderId = UUID.fromString(checkout(buyer, null).get("orderId").asText());
        Order order = orderRepository.findById(orderId).orElseThrow();
        order.setExpiresAt(Instant.now().minusSeconds(60));
        orderRepository.save(order);

        submit(buyer, orderId, uniqueReference(), "LATE BUYER", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_EXPIRED"));
        assertThat(orderRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.INITIATED);
    }

    @Test
    void theInterpreterChangesTheReceivingAccount() throws Exception {
        User interpreter = createUser("insta-settings", Role.INTERPRETER);
        JsonNode before = json(mvc.perform(get(ApiPaths.Admin.INSTAPAY_SETTINGS)
                .header("Authorization", bearer(interpreter))).andExpect(status().isOk()));
        try {
            Map<String, Object> bad = settingsBody(before, "someone@instapay", "https://ipn.eg.evil.com/S/x/instapay/1");
            mvc.perform(put(ApiPaths.Admin.INSTAPAY_SETTINGS).header("Authorization", bearer(interpreter))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(bad)))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("INVALID_LINK"));

            Map<String, Object> good = settingsBody(before, " Mariam.Saad@InstaPay ",
                    "https://ipn.eg/S/mariamsaad/instapay/AbC123");
            mvc.perform(put(ApiPaths.Admin.INSTAPAY_SETTINGS).header("Authorization", bearer(interpreter))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(good)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.address").value("mariam.saad@instapay"));

            User buyer = egyptian("insta-new-account");
            UUID orderId = UUID.fromString(checkout(buyer, null).get("orderId").asText());
            mvc.perform(get(ApiPaths.Me.ORDERS + "/" + orderId + "/instapay").header("Authorization", bearer(buyer)))
                    .andExpect(jsonPath("$.address").value("mariam.saad@instapay"))
                    .andExpect(jsonPath("$.link").value("https://ipn.eg/S/mariamsaad/instapay/AbC123"));
        } finally {
            mvc.perform(put(ApiPaths.Admin.INSTAPAY_SETTINGS).header("Authorization", bearer(interpreter))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(settingsBody(before,
                                    before.get("address").asText(), before.get("link").asText()))))
                    .andExpect(status().isOk());
        }
    }

    // ------------------------------------------------------------------ helpers

    private User egyptian(String name) {
        User user = createUser(name, Role.USER);
        user.setCountryCode("EG");
        return userRepository.save(user);
    }

    /** Approve with the reference the interpreter sees (the current one). */
    private MockHttpServletRequestBuilder approve(UUID orderId) {
        String ref = orderRepository.findById(orderId).map(Order::getTransferReference).orElse(null);
        return post(approvePath(orderId)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reference\":\"" + (ref == null ? "" : ref) + "\"}");
    }

    private static String approvePath(UUID orderId) {
        return ApiPaths.Admin.INSTAPAY_TRANSFERS + "/" + orderId + "/approve";
    }

    private static String uniqueReference() {
        return String.valueOf(100_000_000_000L + (long) (Math.random() * 899_999_999_999L));
    }

    private Map<String, Object> settingsBody(JsonNode current, String address, String link) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("enabled", current.get("enabled").asBoolean());
        body.put("address", address);
        body.put("link", link);
        body.put("recipientName", current.get("recipientName").asText());
        body.put("expireHours", current.get("expireHours").asInt());
        return body;
    }

    private ResultActions submit(User user, UUID orderId, String reference, String sender, List<UUID> dreamIds)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reference", reference);
        body.put("senderName", sender);
        if (dreamIds != null) {
            body.put("dreamIds", dreamIds.stream().map(UUID::toString).toList());
        }
        return mvc.perform(post(ApiPaths.Me.ORDERS + "/" + orderId + "/instapay").header("Authorization", bearer(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private JsonNode checkout(User user, UUID draft) throws Exception {
        Map<String, Object> body = draft == null
                ? Map.of("packageId", PACKAGE_ONE)
                : Map.of("packageId", PACKAGE_ONE, "dreamIds", List.of(draft.toString()));
        return json(mvc.perform(post(ApiPaths.Checkout.ROOT).header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk()));
    }

    private UUID createDraft(User user) throws Exception {
        MvcResult r = mvc.perform(post(ApiPaths.Dreams.ROOT).header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("text", DREAM_TEXT, "gender", "FEMALE"))))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(r.getResponse().getContentAsString()).get("id").asText());
    }

    private JsonNode json(ResultActions actions) throws Exception {
        return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString());
    }
}
