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
import com.saadat.common.audit.repo.AuditLogRepository;
import com.saadat.common.domain.Currency;
import com.saadat.common.domain.DreamStatus;
import com.saadat.common.domain.LedgerReason;
import com.saadat.common.domain.NotificationType;
import com.saadat.common.domain.OrderStatus;
import com.saadat.common.domain.PaymentProviderType;
import com.saadat.common.domain.Role;
import com.saadat.credits.service.CreditService;
import com.saadat.dreams.repo.DreamRepository;
import com.saadat.mail.MailTemplates;
import com.saadat.notifications.domain.Notification;
import com.saadat.notifications.repo.EmailLogRepository;
import com.saadat.notifications.repo.NotificationRepository;
import com.saadat.payments.domain.CreditLedgerEntry;
import com.saadat.payments.domain.Order;
import com.saadat.payments.repo.CreditLedgerRepository;
import com.saadat.payments.repo.OrderRepository;
import com.saadat.payments.service.OrderNumbers;
import com.saadat.users.domain.User;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * docs/PAYMENTS_FILTERS_CONTRACT.md: filters and the UNPAID tab of /admin/orders (§3), and withdrawing the unused
 * credits of a paid order (§2).
 */
class PaymentsFiltersIntegrationTest extends IntegrationTestBase {

    /** Seeded package p1 (1 credit). */
    private static final UUID PACKAGE_ONE = UUID.fromString("22222222-2222-4222-8222-000000000001");
    /** Seeded package p3 (3 credits). */
    private static final UUID PACKAGE_THREE = UUID.fromString("22222222-2222-4222-8222-000000000003");
    /** No other test has orders from Antarctica: the filter tests see only their own rows. */
    private static final String COUNTRY = "AQ";
    private static final String DREAM_TEXT = "رأيت أني أعبر جسراً طويلاً فوق نهر واسع وفي آخره بستان كبير.";

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    OrderRepository orderRepository;

    @Autowired
    OrderNumbers orderNumbers;

    @Autowired
    CreditService creditService;

    @Autowired
    CreditLedgerRepository ledgerRepository;

    @Autowired
    DreamRepository dreamRepository;

    @Autowired
    NotificationRepository notificationRepository;

    @Autowired
    EmailLogRepository emailLogRepository;

    @Autowired
    AuditLogRepository auditLogRepository;

    // ================================================================== §3 filters

    @Test
    void eachFilterAloneAndCombinedWithATabAndTheOrderNumber() throws Exception {
        User interpreter = createUser("filters-interpreter", Role.INTERPRETER);
        User buyer = createUser("filters-buyer", Role.USER);
        Instant now = Instant.now();
        // 2026-03-10T21:30Z is 2026-03-11 00:30 in Riyadh
        Order a = order(buyer, OrderStatus.SUCCESS, PaymentProviderType.MOCK, Currency.USD, "10.00",
                Instant.parse("2026-03-10T21:30:00Z"), null, PACKAGE_ONE);
        Order b = order(buyer, OrderStatus.FAILED, PaymentProviderType.INSTAPAY, Currency.EGP, "20.00",
                Instant.parse("2026-03-12T10:00:00Z"), null, PACKAGE_THREE);
        b.setApprovalRevokedAt(now);
        b.setFailureReason("APPROVAL_REVOKED");
        b = orderRepository.save(b);
        Order c = order(buyer, OrderStatus.PENDING_REVIEW, PaymentProviderType.INSTAPAY, Currency.EGP, "30.00",
                Instant.parse("2026-03-13T10:00:00Z"), null, PACKAGE_ONE);
        c.setApprovalRevokedAt(now);
        c = orderRepository.save(c);
        Order d = order(buyer, OrderStatus.INITIATED, PaymentProviderType.INSTAPAY, Currency.EGP, "40.00",
                now.minus(Duration.ofHours(2)), now.minus(Duration.ofHours(1)), PACKAGE_ONE);
        Order e = order(buyer, OrderStatus.INITIATED, PaymentProviderType.INSTAPAY, Currency.EGP, "50.00",
                now.minus(Duration.ofHours(1)), now.plus(Duration.ofHours(5)), PACKAGE_ONE);
        Order f = order(buyer, OrderStatus.EXPIRED, PaymentProviderType.MOCK, Currency.SAR, "60.00",
                Instant.parse("2026-03-14T10:00:00Z"), null, PACKAGE_ONE);
        Order g = order(buyer, OrderStatus.SUCCESS, PaymentProviderType.INSTAPAY, Currency.EGP, "70.00",
                Instant.parse("2026-03-15T10:00:00Z"), null, PACKAGE_ONE);
        g.setLeftoverWithdrawnAt(now);
        g.setLeftoverWithdrawnCredits(1);
        g = orderRepository.save(g);

        assertThat(ids(interpreter, Map.of())).containsExactlyInAnyOrder(ids(a, b, c, d, e, f, g));
        // status: single, comma list, repeated; a lazily expired booking counts as EXPIRED, not INITIATED
        assertThat(ids(interpreter, Map.of("status", "SUCCESS"))).containsExactlyInAnyOrder(ids(a, g));
        assertThat(ids(interpreter, Map.of("status", "success,failed"))).containsExactlyInAnyOrder(ids(a, b, g));
        assertThat(ids(list(interpreter, Map.of()).param("status", "SUCCESS").param("status", "FAILED")))
                .containsExactlyInAnyOrder(ids(a, b, g));
        assertThat(ids(interpreter, Map.of("status", "EXPIRED"))).containsExactlyInAnyOrder(ids(d, f));
        assertThat(ids(interpreter, Map.of("status", "INITIATED"))).containsExactly(e.getId().toString());
        // other filters
        assertThat(ids(interpreter, Map.of("provider", "MOCK"))).containsExactlyInAnyOrder(ids(a, f));
        assertThat(ids(interpreter, Map.of("currency", "sar"))).containsExactly(f.getId().toString());
        assertThat(ids(interpreter, Map.of("from", "2026-03-11", "to", "2026-03-11")))
                .containsExactly(a.getId().toString());
        assertThat(ids(interpreter, Map.of("to", "2026-03-10"))).isEmpty();
        assertThat(ids(interpreter, Map.of("from", "2026-03-12", "to", "2026-03-14")))
                .containsExactlyInAnyOrder(ids(b, c, f));
        assertThat(ids(interpreter, Map.of("minAmount", "25", "maxAmount", "45"))).containsExactlyInAnyOrder(ids(c, d));
        assertThat(ids(interpreter, Map.of("packageId", PACKAGE_THREE.toString()))).containsExactly(b.getId().toString());
        assertThat(ids(interpreter, Map.of("flag", "REVOKED"))).containsExactlyInAnyOrder(ids(b, c));
        assertThat(ids(interpreter, Map.of("flag", "RESENT"))).containsExactly(c.getId().toString());
        assertThat(ids(interpreter, Map.of("flag", "LEFTOVER_WITHDRAWN"))).containsExactly(g.getId().toString());

        // combined with a tab
        assertThat(ids(interpreter, Map.of("scope", "UNPAID"))).containsExactlyInAnyOrder(ids(d, e, f));
        assertThat(ids(interpreter, Map.of("scope", "UNPAID", "status", "EXPIRED"))).containsExactlyInAnyOrder(ids(d, f));
        assertThat(ids(interpreter, Map.of("scope", "INTERNATIONAL"))).containsExactlyInAnyOrder(ids(a, b, c, g));
        assertThat(ids(interpreter, Map.of("scope", "INTERNATIONAL", "currency", "EGP")))
                .containsExactlyInAnyOrder(ids(b, c, g));
        assertThat(ids(interpreter, Map.of("scope", "REVIEW"))).containsExactly(c.getId().toString());
        // and with the order number
        String digits = c.getOrderNumber().substring(c.getOrderNumber().lastIndexOf('-') + 1);
        assertThat(ids(interpreter, Map.of("scope", "REVIEW", "q", digits))).containsExactly(c.getId().toString());
        assertThat(ids(interpreter, Map.of("scope", "REVIEW", "q", a.getOrderNumber()))).isEmpty();
        assertThat(ids(interpreter, Map.of("q", a.getOrderNumber(), "currency", "USD")))
                .containsExactly(a.getId().toString());
        assertThat(ids(interpreter, Map.of("q", a.getOrderNumber(), "currency", "EGP"))).isEmpty();

        // the JSON: the lazily expired booking is EXPIRED; new row fields
        mvc.perform(list(interpreter, Map.of("scope", "UNPAID")))
                .andExpect(jsonPath("$.items[?(@.id == '" + d.getId() + "')].status").value(org.hamcrest.Matchers.hasItem("EXPIRED")))
                .andExpect(jsonPath("$.items[?(@.id == '" + e.getId() + "')].status").value(org.hamcrest.Matchers.hasItem("INITIATED")))
                .andExpect(jsonPath("$.items[?(@.id == '" + e.getId() + "')].expiresAt").isNotEmpty())
                .andExpect(jsonPath("$.items[?(@.id == '" + e.getId() + "')].packageId")
                        .value(org.hamcrest.Matchers.hasItem(PACKAGE_ONE.toString())));
        mvc.perform(list(interpreter, Map.of("flag", "LEFTOVER_WITHDRAWN")))
                .andExpect(jsonPath("$.items[0].leftoverWithdrawnAt").isNotEmpty());
        mvc.perform(get(ApiPaths.Admin.ORDERS + "/" + d.getId()).header("Authorization", bearer(interpreter)))
                .andExpect(jsonPath("$.status").value("EXPIRED"));

        // counts honour the filters
        JsonNode counts = counts(interpreter, Map.of("country", COUNTRY));
        assertThat(counts.get("review").asLong()).isEqualTo(1);
        assertThat(counts.get("domestic").asLong()).isZero();
        assertThat(counts.get("international").asLong()).isEqualTo(4);
        assertThat(counts.get("unpaid").asLong()).isEqualTo(3);
        counts = counts(interpreter, Map.of("country", COUNTRY, "currency", "EGP"));
        assertThat(counts.get("review").asLong()).isEqualTo(1);
        assertThat(counts.get("international").asLong()).isEqualTo(3);
        assertThat(counts.get("unpaid").asLong()).isEqualTo(2);
        // q is honoured by the counts too (the badges follow the order-number search)
        counts = counts(interpreter, Map.of("country", COUNTRY, "q", c.getOrderNumber()));
        assertThat(counts.get("review").asLong()).isEqualTo(1);
        assertThat(counts.get("international").asLong()).isEqualTo(1);
        assertThat(counts.get("unpaid").asLong()).isZero();
        counts = counts(interpreter, Map.of("country", COUNTRY, "status", "INITIATED,EXPIRED"));
        assertThat(counts.get("unpaid").asLong()).isEqualTo(3);
        assertThat(counts.get("international").asLong()).isZero();
        counts = counts(interpreter, Map.of("country", COUNTRY, "status", "EXPIRED"));
        assertThat(counts.get("unpaid").asLong()).isEqualTo(2);
        assertThat(counts.get("international").asLong()).isZero();
        // without filters the badges keep working (and include unpaid)
        mvc.perform(get(ApiPaths.Admin.ORDER_COUNTS).header("Authorization", bearer(interpreter)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unpaid").value(org.hamcrest.Matchers.greaterThanOrEqualTo(3)));
    }

    @Test
    void invalidFiltersAreRefusedWithTheFieldName() throws Exception {
        User interpreter = createUser("filters-invalid", Role.INTERPRETER);
        Map<String, String> bad = new LinkedHashMap<>();
        bad.put("status", "NOPE");
        bad.put("provider", "PAYPAL");
        bad.put("country", "EGY");
        bad.put("currency", "EUR");
        bad.put("from", "2026-13-01");
        bad.put("minAmount", "abc");
        bad.put("maxAmount", "-5");
        bad.put("packageId", "not-a-uuid");
        bad.put("flag", "STRANGE");
        for (Map.Entry<String, String> entry : bad.entrySet()) {
            mvc.perform(get(ApiPaths.Admin.ORDERS).param(entry.getKey(), entry.getValue())
                            .header("Authorization", bearer(interpreter)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_FILTER"))
                    .andExpect(jsonPath("$.field").value(entry.getKey()));
        }
        mvc.perform(get(ApiPaths.Admin.ORDERS).param("from", "2026-03-10").param("to", "2026-03-01")
                        .header("Authorization", bearer(interpreter)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("to"));
        mvc.perform(get(ApiPaths.Admin.ORDER_COUNTS).param("status", "NOPE").header("Authorization", bearer(interpreter)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_FILTER"));
    }

    @Test
    void domesticAndInternationalLeaveTheUnpaidBookingsToTheirOwnTab() throws Exception {
        User interpreter = createUser("filters-domestic", Role.INTERPRETER);
        User buyer = createUser("filters-domestic-buyer", Role.USER);
        Instant now = Instant.now();
        String amount = "98765.43";
        Order paid = egyptian(order(buyer, OrderStatus.SUCCESS, PaymentProviderType.INSTAPAY, Currency.EGP, amount,
                now.minus(Duration.ofHours(3)), null, PACKAGE_ONE));
        Order booking = egyptian(order(buyer, OrderStatus.INITIATED, PaymentProviderType.INSTAPAY, Currency.EGP, amount,
                now.minus(Duration.ofHours(2)), now.plus(Duration.ofHours(10)), PACKAGE_ONE));
        Order expired = egyptian(order(buyer, OrderStatus.EXPIRED, PaymentProviderType.INSTAPAY, Currency.EGP, amount,
                now.minus(Duration.ofHours(1)), null, PACKAGE_ONE));
        Map<String, String> byAmount = Map.of("minAmount", amount, "maxAmount", amount, "country", "EG");

        assertThat(ids(interpreter, with(byAmount, "scope", "DOMESTIC"))).containsExactly(paid.getId().toString());
        assertThat(ids(interpreter, with(byAmount, "scope", "UNPAID")))
                .containsExactly(expired.getId().toString(), booking.getId().toString());
        assertThat(ids(interpreter, with(byAmount, "scope", "INTERNATIONAL"))).isEmpty();
        JsonNode counts = counts(interpreter, byAmount);
        assertThat(counts.get("domestic").asLong()).isEqualTo(1);
        assertThat(counts.get("unpaid").asLong()).isEqualTo(2);
        assertThat(counts.get("international").asLong()).isZero();
        assertThat(counts.get("review").asLong()).isZero();
    }

    // ================================================================== §2 leftover withdrawal

    @Test
    void theUnusedCreditsOfAPaymentWithAnInterpretedDreamAreWithdrawn() throws Exception {
        User buyer = egyptianUser("leftover-buyer");
        User interpreter = createUser("leftover-interpreter", Role.INTERPRETER);
        UUID d1 = createDraft(buyer);
        UUID d2 = createDraft(buyer);
        UUID orderId = paidOrder(buyer, interpreter, PACKAGE_THREE, d1, d2);
        mvc.perform(post(ApiPaths.Admin.DREAMS + "/" + d1 + "/interpretation").header("Authorization", bearer(interpreter))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"رؤيا خير والله أعلم.\"}"))
                .andExpect(status().isNoContent());
        assertThat(creditService.balance(buyer.getId())).isEqualTo(1);

        mvc.perform(get(ApiPaths.Admin.ORDERS + "/" + orderId).header("Authorization", bearer(interpreter)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.leftoverCredits").value(1))
                .andExpect(jsonPath("$.interpretedDreams").value(1))
                .andExpect(jsonPath("$.canWithdrawLeftover").value(true))
                .andExpect(jsonPath("$.canRevoke").value(false))
                .andExpect(jsonPath("$.revokeBlockedReason").value("DREAM_ALREADY_INTERPRETED"))
                .andExpect(jsonPath("$.leftoverWithdrawnAt").isEmpty())
                .andExpect(jsonPath("$.leftoverWithdrawnCredits").isEmpty());

        // only the interpreter
        withdraw(buyer, orderId, null).andExpect(status().isForbidden());

        withdraw(interpreter, orderId, "The transfer could not be confirmed")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.leftoverCredits").value(0))
                .andExpect(jsonPath("$.canWithdrawLeftover").value(false))
                .andExpect(jsonPath("$.leftoverWithdrawnAt").isNotEmpty())
                .andExpect(jsonPath("$.leftoverWithdrawnCredits").value(1))
                .andExpect(jsonPath("$.leftoverNote").value("The transfer could not be confirmed"))
                .andExpect(jsonPath("$.canRevoke").value(false))
                .andExpect(jsonPath("$.revokeBlockedReason").value("DREAM_ALREADY_INTERPRETED"));
        assertThat(creditService.balance(buyer.getId())).isZero();
        assertThat(dreamRepository.findById(d1).orElseThrow().getStatus()).isEqualTo(DreamStatus.INTERPRETED);
        assertThat(dreamRepository.findById(d2).orElseThrow().getStatus()).isEqualTo(DreamStatus.IN_REVIEW);
        List<CreditLedgerEntry> revokes = ledgerRepository.findAllByOrderIdAndReasonOrderByCreatedAtAsc(orderId,
                LedgerReason.REVOKE);
        assertThat(revokes).hasSize(1);
        assertThat(revokes.get(0).getDelta()).isEqualTo(-1);
        Order order = orderRepository.findById(orderId).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.SUCCESS);
        assertThat(auditLogRepository.findByEntityAndEntityIdOrderByCreatedAtDesc("orders", orderId.toString()))
                .anyMatch(a -> "ORDER_LEFTOVER_WITHDRAWN".equals(a.getAction()));

        // the buyer is told: in-app (+ push) and one e-mail
        awaitTrue("CREDITS_WITHDRAWN", () -> notifications(buyer).stream()
                .anyMatch(n -> n.getType() == NotificationType.CREDITS_WITHDRAWN));
        Notification n = notifications(buyer).stream().filter(x -> x.getType() == NotificationType.CREDITS_WITHDRAWN)
                .findFirst().orElseThrow();
        assertThat(n.getBody()).isEqualTo("تم خصم رؤيا واحدة من رصيد الطلب " + order.getOrderNumber()
                + " لأننا لم نتمكن من تأكيد الدفع. رؤاك التي فُسِّرت أو التي قيد التعبير لا تتأثر. للاستفسار تواصل معنا.");
        assertThat(n.getLink()).isEqualTo("/me/payments");
        awaitTrue("credits-withdrawn e-mail", () -> withdrawnMails(orderId) == 1);

        // nothing left: a second call is refused, and the filter finds it
        withdraw(interpreter, orderId, null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOTHING_TO_WITHDRAW"));
        mvc.perform(get(ApiPaths.Admin.ORDERS).param("flag", "LEFTOVER_WITHDRAWN").param("q", order.getOrderNumber())
                        .header("Authorization", bearer(interpreter)))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].leftoverWithdrawnAt").isNotEmpty());
        assertThat(withdrawnMails(orderId)).isEqualTo(1);
    }

    @Test
    void onlyAPaidOrderWithUnexpiredLeftoverCanBeWithdrawn() throws Exception {
        User buyer = egyptianUser("leftover-refused");
        User interpreter = createUser("leftover-refused-interpreter", Role.INTERPRETER);
        withdraw(interpreter, UUID.randomUUID(), null).andExpect(status().isNotFound());

        // not paid
        JsonNode checkout = json(mvc.perform(post(ApiPaths.Checkout.ROOT).header("Authorization", bearer(buyer))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("packageId", PACKAGE_ONE.toString()))))
                .andExpect(status().isOk()));
        UUID pending = UUID.fromString(checkout.get("orderId").asText());
        submitTransfer(buyer, pending, uniqueReference()).andExpect(status().isOk());
        withdraw(interpreter, pending, null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_WITHDRAWABLE"));

        // paid, but its credits expired
        UUID orderId = paidOrder(buyer, interpreter, PACKAGE_THREE);
        CreditLedgerEntry purchase = ledgerRepository.findAllByOrderIdAndReasonOrderByCreatedAtAsc(orderId,
                LedgerReason.PURCHASE).get(0);
        purchase.setExpiresAt(Instant.now().minus(Duration.ofDays(1)));
        ledgerRepository.save(purchase);
        mvc.perform(get(ApiPaths.Admin.ORDERS + "/" + orderId).header("Authorization", bearer(interpreter)))
                .andExpect(jsonPath("$.leftoverCredits").value(0))
                .andExpect(jsonPath("$.canWithdrawLeftover").value(false));
        withdraw(interpreter, orderId, null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOTHING_TO_WITHDRAW"));
        assertThat(ledgerRepository.findAllByOrderIdAndReasonOrderByCreatedAtAsc(orderId, LedgerReason.REVOKE)).isEmpty();
    }

    @Test
    void aReapprovalAfterAnUndoDoesNotBringBackTheWithdrawnLeftover() throws Exception {
        User buyer = egyptianUser("leftover-undo");
        User interpreter = createUser("leftover-undo-interpreter", Role.INTERPRETER);
        UUID d1 = createDraft(buyer);
        JsonNode checkout = json(mvc.perform(post(ApiPaths.Checkout.ROOT).header("Authorization", bearer(buyer))
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(
                        Map.of("packageId", PACKAGE_THREE.toString(), "dreamIds", List.of(d1.toString())))))
                .andExpect(status().isOk()));
        UUID orderId = UUID.fromString(checkout.get("orderId").asText());
        String reference = uniqueReference();
        submitTransfer(buyer, orderId, reference).andExpect(status().isOk());
        approve(interpreter, orderId, reference).andExpect(status().isOk());
        assertThat(creditService.balance(buyer.getId())).isEqualTo(2);

        withdraw(interpreter, orderId, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.leftoverWithdrawnCredits").value(2));
        // a gift unrelated to the order stays through everything below
        creditService.add(buyer.getId(), 1, LedgerReason.BONUS, null, "gift", null);
        int afterWithdrawal = creditService.balance(buyer.getId());
        assertThat(afterWithdrawal).isEqualTo(1);

        mvc.perform(post(ApiPaths.Admin.INSTAPAY_TRANSFERS + "/" + orderId + "/revoke")
                        .header("Authorization", bearer(interpreter)).contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.heldDreams").value(1));
        assertThat(dreamRepository.findById(d1).orElseThrow().getStatus()).isEqualTo(DreamStatus.AWAITING_PAYMENT);
        assertThat(creditService.balance(buyer.getId())).isEqualTo(afterWithdrawal);

        submitTransfer(buyer, orderId, reference).andExpect(status().isOk());
        approve(interpreter, orderId, reference).andExpect(status().isOk());
        assertThat(dreamRepository.findById(d1).orElseThrow().getStatus()).isEqualTo(DreamStatus.IN_REVIEW);
        assertThat(creditService.balance(buyer.getId())).isEqualTo(afterWithdrawal);
        List<CreditLedgerEntry> purchases = ledgerRepository.findAllByOrderIdAndReasonOrderByCreatedAtAsc(orderId,
                LedgerReason.PURCHASE);
        assertThat(purchases).extracting(CreditLedgerEntry::getDelta).containsExactly(3, 1);
        // the dream's credit came from this order; the order page still shows the package's 3 credits, none left
        CreditLedgerEntry debit = ledgerRepository.findById(
                dreamRepository.findById(d1).orElseThrow().getLedgerEntryId()).orElseThrow();
        assertThat(debit.getOrderId()).isEqualTo(orderId);
        mvc.perform(get(ApiPaths.Admin.DREAMS + "/" + d1).header("Authorization", bearer(interpreter)))
                .andExpect(jsonPath("$.payment.credits").value(3))
                .andExpect(jsonPath("$.payment.creditsLeft").value(0));
        mvc.perform(get(ApiPaths.Admin.ORDERS + "/" + orderId).header("Authorization", bearer(interpreter)))
                .andExpect(jsonPath("$.leftoverCredits").value(0))
                .andExpect(jsonPath("$.canWithdrawLeftover").value(false));
    }

    private ResultActions approve(User interpreter, UUID orderId, String reference) throws Exception {
        return mvc.perform(post(ApiPaths.Admin.INSTAPAY_TRANSFERS + "/" + orderId + "/approve")
                .header("Authorization", bearer(interpreter)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reference\":\"" + reference + "\"}"));
    }

    // ================================================================== helpers

    private Order order(User user, OrderStatus status, PaymentProviderType provider, Currency currency, String amount,
                        Instant createdAt, Instant expiresAt, UUID packageId) {
        Order o = new Order();
        o.setOrderNumber(orderNumbers.next());
        o.setUserId(user.getId());
        o.setPackageId(packageId);
        o.setPackageNameSnapshot("Test package");
        o.setCredits(1);
        o.setAmount(new BigDecimal(amount));
        o.setCurrency(currency);
        o.setProvider(provider);
        o.setStatus(status);
        o.setCountryCode(COUNTRY);
        o.setCreatedAt(createdAt);
        o.setExpiresAt(expiresAt == null ? createdAt.plus(Duration.ofHours(1)) : expiresAt);
        if (status == OrderStatus.SUCCESS) {
            o.setPaidAt(createdAt);
        }
        return orderRepository.save(o);
    }

    private Order egyptian(Order o) {
        o.setCountryCode("EG");
        return orderRepository.save(o);
    }

    private static String[] ids(Order... orders) {
        return java.util.Arrays.stream(orders).map(o -> o.getId().toString()).toArray(String[]::new);
    }

    private static Map<String, String> with(Map<String, String> base, String key, String value) {
        Map<String, String> m = new LinkedHashMap<>(base);
        m.put(key, value);
        return m;
    }

    /** GET /admin/orders with the filters; the test's own country unless another one is given. */
    private MockHttpServletRequestBuilder list(User interpreter, Map<String, String> params) {
        MockHttpServletRequestBuilder request = get(ApiPaths.Admin.ORDERS).param("size", "100")
                .header("Authorization", bearer(interpreter));
        if (!params.containsKey("country")) {
            request = request.param("country", COUNTRY);
        }
        for (Map.Entry<String, String> e : params.entrySet()) {
            request = request.param(e.getKey(), e.getValue());
        }
        return request;
    }

    private List<String> ids(User interpreter, Map<String, String> params) throws Exception {
        return ids(list(interpreter, params));
    }

    private List<String> ids(MockHttpServletRequestBuilder request) throws Exception {
        JsonNode page = json(mvc.perform(request).andExpect(status().isOk()));
        List<String> out = new ArrayList<>();
        page.get("items").forEach(i -> out.add(i.get("id").asText()));
        return out;
    }

    private JsonNode counts(User interpreter, Map<String, String> params) throws Exception {
        MockHttpServletRequestBuilder request = get(ApiPaths.Admin.ORDER_COUNTS).header("Authorization", bearer(interpreter));
        for (Map.Entry<String, String> e : params.entrySet()) {
            request = request.param(e.getKey(), e.getValue());
        }
        return json(mvc.perform(request).andExpect(status().isOk()));
    }

    private long withdrawnMails(UUID orderId) {
        return emailLogRepository.findByTemplateOrderByCreatedAtDesc(MailTemplates.CREDITS_WITHDRAWN).stream()
                .filter(m -> m.getRef() != null && m.getRef().startsWith(MailTemplates.CREDITS_WITHDRAWN + ":" + orderId))
                .count();
    }

    private List<Notification> notifications(User user) {
        return notificationRepository.findByUserIdOrderByCreatedAtDesc(user.getId(), Pageable.unpaged()).getContent();
    }

    private User egyptianUser(String name) {
        User user = createUser(name, Role.USER);
        user.setCountryCode("EG");
        return userRepository.save(user);
    }

    /** Checkout (InstaPay, Egypt) with the drafts, transfer sent and approved. */
    private UUID paidOrder(User buyer, User interpreter, UUID packageId, UUID... drafts) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("packageId", packageId.toString());
        if (drafts.length > 0) {
            body.put("dreamIds", java.util.Arrays.stream(drafts).map(UUID::toString).toList());
        }
        JsonNode checkout = json(mvc.perform(post(ApiPaths.Checkout.ROOT).header("Authorization", bearer(buyer))
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk()));
        UUID orderId = UUID.fromString(checkout.get("orderId").asText());
        String reference = uniqueReference();
        submitTransfer(buyer, orderId, reference).andExpect(status().isOk());
        mvc.perform(post(ApiPaths.Admin.INSTAPAY_TRANSFERS + "/" + orderId + "/approve")
                        .header("Authorization", bearer(interpreter)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reference\":\"" + reference + "\"}"))
                .andExpect(status().isOk());
        return orderId;
    }

    private ResultActions submitTransfer(User user, UUID orderId, String reference) throws Exception {
        return mvc.perform(post(ApiPaths.Me.ORDERS + "/" + orderId + "/instapay").header("Authorization", bearer(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("reference", reference, "senderName", "BUYER NAME"))));
    }

    private ResultActions withdraw(User user, UUID orderId, String note) throws Exception {
        return mvc.perform(post("/admin/payments/orders/" + orderId + "/withdraw-leftover")
                .header("Authorization", bearer(user)).contentType(MediaType.APPLICATION_JSON)
                .content(note == null ? "{}" : objectMapper.writeValueAsString(Map.of("note", note))));
    }

    private static String uniqueReference() {
        return String.valueOf(100_000_000_000L + (long) (Math.random() * 899_999_999_999L));
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
