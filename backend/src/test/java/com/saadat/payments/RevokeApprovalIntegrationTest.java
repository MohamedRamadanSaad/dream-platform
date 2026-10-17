package com.saadat.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.saadat.IntegrationTestBase;
import com.saadat.common.api.ApiPaths;
import com.saadat.common.audit.repo.AuditLogRepository;
import com.saadat.common.domain.CouponType;
import com.saadat.common.domain.DreamStatus;
import com.saadat.common.domain.LedgerReason;
import com.saadat.common.domain.NotificationType;
import com.saadat.common.domain.OrderStatus;
import com.saadat.common.domain.PaymentProviderType;
import com.saadat.common.domain.Role;
import com.saadat.credits.service.CreditExpiryService;
import com.saadat.credits.service.CreditService;
import com.saadat.push.service.WebPushSender;
import com.saadat.dreams.domain.Dream;
import com.saadat.dreams.repo.DreamRepository;
import com.saadat.mail.MailTemplates;
import com.saadat.notifications.domain.Notification;
import com.saadat.notifications.repo.EmailLogRepository;
import com.saadat.notifications.repo.NotificationRepository;
import com.saadat.payments.domain.CreditLedgerEntry;
import com.saadat.payments.domain.Order;
import com.saadat.payments.repo.CreditLedgerRepository;
import com.saadat.payments.repo.OrderRepository;
import com.saadat.pricing.domain.Coupon;
import com.saadat.pricing.repo.CouponRedemptionRepository;
import com.saadat.pricing.repo.CouponRepository;
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
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * docs/REVOKE_APPROVAL_CONTRACT.md: the interpreter takes back a mistaken InstaPay approval. The dreams it paid wait
 * as AWAITING_PAYMENT, its credits are withdrawn, the buyer resends the details and a new approval resumes them.
 */
class RevokeApprovalIntegrationTest extends IntegrationTestBase {

    /** Seeded package p1 (1 credit). */
    private static final String PACKAGE_ONE = "22222222-2222-4222-8222-000000000001";
    /** Seeded package p3 (3 credits). */
    private static final String PACKAGE_THREE = "22222222-2222-4222-8222-000000000003";
    private static final String DREAM_TEXT = "رأيت أني أصعد جبلاً أخضر وفي أعلاه بئر ماؤها بارد وصافٍ جداً.";

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    CreditService creditService;

    @Autowired
    DreamRepository dreamRepository;

    @Autowired
    OrderRepository orderRepository;

    @Autowired
    CreditLedgerRepository ledgerRepository;

    @Autowired
    NotificationRepository notificationRepository;

    @Autowired
    EmailLogRepository emailLogRepository;

    @Autowired
    AuditLogRepository auditLogRepository;

    @Autowired
    CouponRepository couponRepository;

    @Autowired
    CouponRedemptionRepository redemptionRepository;

    @Autowired
    CreditExpiryService creditExpiryService;

    @SpyBean
    WebPushSender webPushSender;

    // ================================================================== revoke → resend → approve again

    @Test
    void revokeHoldsTheDreamsAndTheNextApprovalResumesThem() throws Exception {
        User buyer = egyptian("revoke-buyer");
        User interpreter = createUser("revoke-interpreter", Role.INTERPRETER);
        UUID d1 = createDraft(buyer);
        UUID d2 = createDraft(buyer);
        assertThat(creditService.balance(buyer.getId())).isZero();

        JsonNode summaryBefore = summary(interpreter);
        UUID orderId = checkout(buyer, PACKAGE_THREE, d1, d2);
        String reference = uniqueReference();
        submitTransfer(buyer, orderId, reference, null).andExpect(status().isOk());
        approve(interpreter, orderId, reference).andExpect(status().isOk());
        assertThat(dreamStatus(d1)).isEqualTo(DreamStatus.IN_REVIEW);
        assertThat(dreamStatus(d2)).isEqualTo(DreamStatus.IN_REVIEW);
        assertThat(creditService.balance(buyer.getId())).isEqualTo(1);
        start(interpreter, d1, 3).andExpect(status().isOk());
        Dream started = dreamRepository.findById(d1).orElseThrow();
        Instant startedAt = started.getStartedAt();
        Instant expectedByBefore = started.getExpectedBy();
        BigDecimal settled = orderRepository.findById(orderId).orElseThrow().getSettledAmountEgp();
        assertThat(settled).isNotNull();

        JsonNode summaryPaid = summary(interpreter);
        assertThat(summaryPaid.get("revenueEgp").asDouble())
                .isEqualTo(summaryBefore.get("revenueEgp").asDouble() + settled.doubleValue(),
                        org.assertj.core.data.Offset.offset(0.01));
        mvc.perform(get(ApiPaths.Admin.ORDERS + "/" + orderId).header("Authorization", bearer(interpreter)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canRevoke").value(true))
                .andExpect(jsonPath("$.revokeBlockedReason").isEmpty());

        // the interpreter takes the approval back
        revoke(interpreter, orderId, "The amount in the transfer is different")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureReason").value("APPROVAL_REVOKED"))
                .andExpect(jsonPath("$.reviewNote").value("The amount in the transfer is different"))
                .andExpect(jsonPath("$.heldDreams").value(2));

        Order revoked = orderRepository.findById(orderId).orElseThrow();
        assertThat(revoked.getStatus()).isEqualTo(OrderStatus.FAILED);
        assertThat(revoked.getFailureReason()).isEqualTo("APPROVAL_REVOKED");
        assertThat(revoked.getSettledAmountEgp()).isNull();
        assertThat(revoked.getPaidAt()).isNull();
        assertThat(revoked.getProviderTxnId()).isNull();
        assertThat(revoked.getReviewedBy()).isEqualTo(interpreter.getId());
        for (UUID id : List.of(d1, d2)) {
            Dream d = dreamRepository.findById(id).orElseThrow();
            assertThat(d.getStatus()).isEqualTo(DreamStatus.AWAITING_PAYMENT);
            assertThat(d.getHeldOrderId()).isEqualTo(orderId);
            assertThat(d.getSlaPausedAt()).isNotNull();
            assertThat(d.getText()).isEqualTo(DREAM_TEXT);
        }
        assertThat(dreamRepository.findById(d1).orElseThrow().getStartedAt()).isEqualTo(startedAt);
        assertThat(creditService.balance(buyer.getId())).isZero();
        assertThat(ledgerRepository.findAllByOrderIdOrderByCreatedAtAsc(orderId))
                .anyMatch(e -> e.getReason() == LedgerReason.REVOKE);

        // the buyer is told (in-app + push + e-mail), the decision is audited
        awaitTrue("PAYMENT_REVOKED notification", () -> notifications(buyer).stream()
                .anyMatch(n -> n.getType() == NotificationType.PAYMENT_REVOKED));
        Notification n = notifications(buyer).stream().filter(x -> x.getType() == NotificationType.PAYMENT_REVOKED)
                .findFirst().orElseThrow();
        assertThat(n.getLink()).isEqualTo("/me/checkout/instapay/" + orderId);
        String number = revoked.getOrderNumber() == null ? orderId.toString() : revoked.getOrderNumber();
        assertThat(n.getBody()).isEqualTo("لم نتمكن من تأكيد دفعتك " + number
                + "، راجع بيانات التحويل وأعد إرسالها. رؤاك محفوظة.");
        verify(webPushSender, timeout(10_000).atLeastOnce()).sendToUser(eq(buyer.getId()), anyString(),
                eq(n.getBody()), eq("/me/checkout/instapay/" + orderId));
        awaitTrue("payment-review-needed e-mail", () -> emailLogRepository.existsByTemplateAndRefStartingWith(
                MailTemplates.PAYMENT_REVIEW_NEEDED, MailTemplates.PAYMENT_REVIEW_NEEDED + ":" + orderId));
        assertThat(auditLogRepository.findByEntityAndEntityIdOrderByCreatedAtDesc("orders", orderId.toString()))
                .anyMatch(a -> "INSTAPAY_REVOKED".equals(a.getAction()));
        assertThat(auditLogRepository.findByEntityAndEntityIdOrderByCreatedAtDesc("dreams", d1.toString()))
                .anyMatch(a -> "DREAM_HELD".equals(a.getAction()));

        // the interpreter's queue tab "Waiting for payment" and its count
        mvc.perform(get(ApiPaths.Admin.DREAMS).param("status", "AWAITING_PAYMENT").param("size", "100")
                        .header("Authorization", bearer(interpreter)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.id == '" + d1 + "')].heldOrderId")
                        .value(org.hamcrest.Matchers.hasItem(orderId.toString())))
                .andExpect(jsonPath("$.items[?(@.id == '" + d2 + "')].heldAt").isNotEmpty());
        assertThat(summary(interpreter).get("awaitingPayment").asLong()).isGreaterThanOrEqualTo(2);
        // the undo history on the order page and in the table
        mvc.perform(get(ApiPaths.Admin.ORDERS + "/" + orderId).header("Authorization", bearer(interpreter)))
                .andExpect(jsonPath("$.revokedAt").isNotEmpty())
                .andExpect(jsonPath("$.revokeNote").value("The amount in the transfer is different"))
                .andExpect(jsonPath("$.heldDreams").value(2));
        // a revoked order is FAILED: it waits in the last tab, Unpaid bookings (owner rule 2026-10-17)
        mvc.perform(get(ApiPaths.Admin.ORDERS).param("scope", "UNPAID").param("size", "100")
                        .header("Authorization", bearer(interpreter)))
                .andExpect(jsonPath("$.items[?(@.id == '" + orderId + "')].failureReason")
                        .value(org.hamcrest.Matchers.hasItem("APPROVAL_REVOKED")))
                .andExpect(jsonPath("$.items[?(@.id == '" + orderId + "')].heldDreams")
                        .value(org.hamcrest.Matchers.hasItem(2)));

        // the dreamer sees them waiting for the payment, marked REVOKED
        assertThat(listIds(buyer, "PENDING_PAYMENT")).contains(d1, d2);
        mvc.perform(get(ApiPaths.Dreams.ROOT).param("status", "PENDING_PAYMENT").header("Authorization", bearer(buyer)))
                .andExpect(jsonPath("$.items[?(@.id == '" + d1 + "')].payment.status")
                        .value(org.hamcrest.Matchers.hasItem("REVOKED")))
                .andExpect(jsonPath("$.items[?(@.id == '" + d1 + "')].payment.orderId")
                        .value(org.hamcrest.Matchers.hasItem(orderId.toString())))
                .andExpect(jsonPath("$.items[?(@.id == '" + d1 + "')].editable")
                        .value(org.hamcrest.Matchers.hasItem(false)))
                .andExpect(jsonPath("$.items[?(@.id == '" + d2 + "')].editable")
                        .value(org.hamcrest.Matchers.hasItem(true)));
        mvc.perform(get(ApiPaths.Dreams.ROOT + "/" + d2).header("Authorization", bearer(buyer)))
                .andExpect(jsonPath("$.status").value("AWAITING_PAYMENT"))
                .andExpect(jsonPath("$.payment.status").value("REVOKED"))
                .andExpect(jsonPath("$.payment.reviewNote").value("The amount in the transfer is different"));
        mvc.perform(get(ApiPaths.Me.DASHBOARD).header("Authorization", bearer(buyer)))
                .andExpect(jsonPath("$.pendingPayment").value(2))
                .andExpect(jsonPath("$.inReview").value(0))
                .andExpect(jsonPath("$.inProgress").value(0));
        mvc.perform(get(ApiPaths.Me.ORDERS + "/" + orderId + "/instapay").header("Authorization", bearer(buyer)))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureReason").value("APPROVAL_REVOKED"))
                .andExpect(jsonPath("$.heldDreams").value(2));
        // the buyer's payment history shows why
        mvc.perform(get(ApiPaths.Me.ORDERS).param("size", "100").header("Authorization", bearer(buyer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.id == '" + orderId + "')].failureReason")
                        .value(org.hamcrest.Matchers.hasItem("APPROVAL_REVOKED")))
                .andExpect(jsonPath("$.items[?(@.id == '" + orderId + "')].status")
                        .value(org.hamcrest.Matchers.hasItem("FAILED")));

        // not open work any more; the revenue no longer counts the order
        JsonNode summaryRevoked = summary(interpreter);
        assertThat(summaryRevoked.get("inReview").asLong()).isEqualTo(summaryPaid.get("inReview").asLong() - 1);
        assertThat(summaryRevoked.get("inProgress").asLong()).isEqualTo(summaryPaid.get("inProgress").asLong() - 1);
        assertThat(summaryRevoked.get("revenueEgp").asDouble())
                .isEqualTo(summaryBefore.get("revenueEgp").asDouble(), org.assertj.core.data.Offset.offset(0.01));
        mvc.perform(get(ApiPaths.Admin.DREAMS + "/" + d1).header("Authorization", bearer(interpreter)))
                .andExpect(jsonPath("$.status").value("AWAITING_PAYMENT"))
                .andExpect(jsonPath("$.heldOrderId").value(orderId.toString()));
        mvc.perform(get(ApiPaths.Admin.DREAMS).param("status", "IN_REVIEW").param("size", "200")
                        .header("Authorization", bearer(interpreter)))
                .andExpect(jsonPath("$.items[?(@.id == '" + d2 + "')]").isEmpty());
        mvc.perform(get(ApiPaths.Admin.ORDERS + "/" + orderId).header("Authorization", bearer(interpreter)))
                .andExpect(jsonPath("$.canRevoke").value(false))
                .andExpect(jsonPath("$.dreams.length()").value(2));

        // the interpreter cannot work on a dream that waits for its payment
        start(interpreter, d2, 2).andExpect(status().isConflict());
        mvc.perform(post(ApiPaths.Admin.DREAMS + "/" + d2 + "/messages").header("Authorization", bearer(interpreter))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"سؤال؟\"}"))
                .andExpect(status().isConflict());
        mvc.perform(post(ApiPaths.Admin.DREAMS + "/" + d1 + "/interpretation").header("Authorization", bearer(interpreter))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"رؤيا خير والله أعلم.\"}"))
                .andExpect(status().isConflict());
        // a second revoke is refused (the order is no longer approved)
        revoke(interpreter, orderId, null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_REVOCABLE"));

        // the dreamer resends the SAME reference: back to the interpreter's review tab
        submitTransfer(buyer, orderId, reference, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_REVIEW"));
        mvc.perform(get(ApiPaths.Dreams.ROOT + "/" + d1).header("Authorization", bearer(buyer)))
                .andExpect(jsonPath("$.payment.status").value("PENDING_REVIEW"));
        // the undo history survives the resend
        mvc.perform(get(ApiPaths.Me.ORDERS + "/" + orderId + "/instapay").header("Authorization", bearer(buyer)))
                .andExpect(jsonPath("$.revokedAt").isNotEmpty())
                .andExpect(jsonPath("$.revokeNote").value("The amount in the transfer is different"))
                .andExpect(jsonPath("$.heldDreams").value(2))
                .andExpect(jsonPath("$.attachedDreams").value(0));

        // held for one hour: the expected date moves forward by that time
        Dream held = dreamRepository.findById(d1).orElseThrow();
        held.setSlaPausedAt(Instant.now().minus(Duration.ofHours(1)));
        dreamRepository.save(held);

        approve(interpreter, orderId, reference).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
        Dream resumed1 = dreamRepository.findById(d1).orElseThrow();
        Dream resumed2 = dreamRepository.findById(d2).orElseThrow();
        assertThat(resumed1.getStatus()).isEqualTo(DreamStatus.IN_PROGRESS);
        assertThat(resumed1.getStartedAt()).isEqualTo(startedAt);
        assertThat(resumed1.getExpectedBy()).isAfter(expectedByBefore.plus(Duration.ofMinutes(59)));
        assertThat(resumed2.getStatus()).isEqualTo(DreamStatus.IN_REVIEW);
        for (Dream d : List.of(resumed1, resumed2)) {
            assertThat(d.getHeldOrderId()).isNull();
            assertThat(d.getSlaPausedAt()).isNull();
            CreditLedgerEntry debit = ledgerRepository.findById(d.getLedgerEntryId()).orElseThrow();
            assertThat(debit.getReason()).isEqualTo(LedgerReason.SUBMIT);
            assertThat(debit.getOrderId()).isEqualTo(orderId);
        }
        assertThat(creditService.balance(buyer.getId())).isEqualTo(1);
        Order again = orderRepository.findById(orderId).orElseThrow();
        assertThat(again.getStatus()).isEqualTo(OrderStatus.SUCCESS);
        assertThat(again.getSettledAmountEgp()).isNotNull();
        assertThat(again.getPaidAt()).isNotNull();
        assertThat(ledgerRepository.findAllByOrderIdAndReasonOrderByCreatedAtAsc(orderId, LedgerReason.PURCHASE))
                .hasSize(2);
        assertThat(auditLogRepository.findByEntityAndEntityIdOrderByCreatedAtDesc("dreams", d2.toString()))
                .anyMatch(a -> "DREAM_RESUMED".equals(a.getAction()));

        // counted once: revenue back to the paid value, the counter of the order is 3 → 1 left
        JsonNode summaryAgain = summary(interpreter);
        assertThat(summaryAgain.get("revenueEgp").asDouble())
                .isEqualTo(summaryPaid.get("revenueEgp").asDouble(), org.assertj.core.data.Offset.offset(0.01));
        assertThat(summaryAgain.get("inReview").asLong()).isEqualTo(summaryPaid.get("inReview").asLong());
        assertThat(summaryAgain.get("inProgress").asLong()).isEqualTo(summaryPaid.get("inProgress").asLong());
        mvc.perform(get(ApiPaths.Admin.DREAMS + "/" + d2).header("Authorization", bearer(interpreter)))
                .andExpect(jsonPath("$.heldOrderId").isEmpty())
                .andExpect(jsonPath("$.payment.credits").value(3))
                .andExpect(jsonPath("$.payment.creditsLeft").value(1));
        mvc.perform(get(ApiPaths.Me.DASHBOARD).header("Authorization", bearer(buyer)))
                .andExpect(jsonPath("$.pendingPayment").value(0))
                .andExpect(jsonPath("$.inReview").value(1))
                .andExpect(jsonPath("$.inProgress").value(1));
        assertThat(listIds(buyer, "PENDING_PAYMENT")).isEmpty();

        // the order can be taken back again, and the dreams can be cancelled afterwards (refund once)
        mvc.perform(get(ApiPaths.Admin.ORDERS + "/" + orderId).header("Authorization", bearer(interpreter)))
                .andExpect(jsonPath("$.canRevoke").value(true));
        mvc.perform(post(ApiPaths.Admin.DREAMS + "/" + d2 + "/cancel").header("Authorization", bearer(interpreter))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"duplicate\"}"))
                .andExpect(status().isNoContent());
        assertThat(creditService.balance(buyer.getId())).isEqualTo(2);
    }

    // ================================================================== refusals

    @Test
    void revokeIsRefusedWhenNotApprovedNotInstapayInterpretedOrNotTheInterpreter() throws Exception {
        User buyer = egyptian("revoke-refused");
        User interpreter = createUser("revoke-refused-interpreter", Role.INTERPRETER);

        // not approved yet
        UUID pending = checkout(buyer, PACKAGE_ONE);
        String pendingRef = uniqueReference();
        submitTransfer(buyer, pending, pendingRef, null).andExpect(status().isOk());
        revoke(interpreter, pending, null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_REVOCABLE"));
        // unknown order
        revoke(interpreter, UUID.randomUUID(), null).andExpect(status().isNotFound());

        // approved, but its dream was already interpreted
        UUID draft = createDraft(buyer);
        UUID orderId = checkout(buyer, PACKAGE_ONE, draft);
        String reference = uniqueReference();
        submitTransfer(buyer, orderId, reference, null).andExpect(status().isOk());
        approve(interpreter, orderId, reference).andExpect(status().isOk());
        // only the interpreter may take it back
        revoke(buyer, orderId, null).andExpect(status().isForbidden());
        mvc.perform(post(ApiPaths.Admin.DREAMS + "/" + draft + "/interpretation").header("Authorization", bearer(interpreter))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"رؤيا خير والله أعلم.\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(get(ApiPaths.Admin.ORDERS + "/" + orderId).header("Authorization", bearer(interpreter)))
                .andExpect(jsonPath("$.canRevoke").value(false))
                .andExpect(jsonPath("$.revokeBlockedReason").value("DREAM_ALREADY_INTERPRETED"));
        revoke(interpreter, orderId, null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DREAM_ALREADY_INTERPRETED"));
        assertThat(orderRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.SUCCESS);
        assertThat(dreamStatus(draft)).isEqualTo(DreamStatus.INTERPRETED);

        // an approved order of another provider
        UUID other = checkout(buyer, PACKAGE_ONE);
        String otherRef = uniqueReference();
        submitTransfer(buyer, other, otherRef, null).andExpect(status().isOk());
        approve(interpreter, other, otherRef).andExpect(status().isOk());
        Order mock = orderRepository.findById(other).orElseThrow();
        mock.setProvider(PaymentProviderType.MOCK);
        orderRepository.save(mock);
        revoke(interpreter, other, null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_REVOCABLE"));
        mvc.perform(get(ApiPaths.Admin.ORDERS + "/" + other).header("Authorization", bearer(interpreter)))
                .andExpect(jsonPath("$.canRevoke").value(false));
    }

    // ================================================================== reject after the resend

    @Test
    void aRejectionAfterTheResendKeepsTheDreamsHeldAndTheBuyerMayResendAgain() throws Exception {
        User buyer = egyptian("revoke-reject");
        User interpreter = createUser("revoke-reject-interpreter", Role.INTERPRETER);
        UUID draft = createDraft(buyer);
        UUID orderId = checkout(buyer, PACKAGE_ONE, draft);
        String reference = uniqueReference();
        submitTransfer(buyer, orderId, reference, null).andExpect(status().isOk());
        approve(interpreter, orderId, reference).andExpect(status().isOk());
        revoke(interpreter, orderId, null).andExpect(status().isOk());

        String corrected = uniqueReference();
        submitTransfer(buyer, orderId, corrected, null).andExpect(status().isOk());
        reject(interpreter, orderId, "Still no transfer").andExpect(status().isOk());

        Dream d = dreamRepository.findById(draft).orElseThrow();
        assertThat(d.getStatus()).isEqualTo(DreamStatus.AWAITING_PAYMENT);
        assertThat(d.getHeldOrderId()).isEqualTo(orderId);
        mvc.perform(get(ApiPaths.Dreams.ROOT + "/" + draft).header("Authorization", bearer(buyer)))
                .andExpect(jsonPath("$.payment.status").value("REJECTED"))
                .andExpect(jsonPath("$.payment.reviewNote").value("Still no transfer"));
        assertThat(creditService.balance(buyer.getId())).isZero();

        // resend again, then approve: the dream resumes
        submitTransfer(buyer, orderId, corrected, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_REVIEW"));
        approve(interpreter, orderId, corrected).andExpect(status().isOk());
        assertThat(dreamStatus(draft)).isEqualTo(DreamStatus.IN_REVIEW);
        assertThat(creditService.balance(buyer.getId())).isZero();
    }

    // ================================================================== resumed with other credits, cancelled

    @Test
    void aHeldDreamCanBeSentWithOtherCreditsOrCancelledWithoutARefund() throws Exception {
        User buyer = egyptian("revoke-submit");
        User interpreter = createUser("revoke-submit-interpreter", Role.INTERPRETER);
        UUID d1 = createDraft(buyer);
        UUID d2 = createDraft(buyer);
        UUID orderId = checkout(buyer, PACKAGE_THREE, d1, d2);
        String reference = uniqueReference();
        submitTransfer(buyer, orderId, reference, null).andExpect(status().isOk());
        approve(interpreter, orderId, reference).andExpect(status().isOk());
        revoke(interpreter, orderId, null).andExpect(status().isOk());
        assertThat(creditService.balance(buyer.getId())).isZero();

        // no credits: 402 and nothing changes
        submitDreams(buyer, d1).andExpect(status().isPaymentRequired());
        assertThat(dreamStatus(d1)).isEqualTo(DreamStatus.AWAITING_PAYMENT);

        // credits from elsewhere resume it and it leaves the held order
        creditService.add(buyer.getId(), 1, LedgerReason.BONUS, null, "test", null);
        submitDreams(buyer, d1).andExpect(status().isOk())
                .andExpect(jsonPath("$.submitted[0]").value(d1.toString()))
                .andExpect(jsonPath("$.remainingCredits").value(0));
        Dream sent = dreamRepository.findById(d1).orElseThrow();
        assertThat(sent.getStatus()).isEqualTo(DreamStatus.IN_REVIEW);
        assertThat(sent.getHeldOrderId()).isNull();

        // the interpreter cancels the other held dream: no refund (its credit was already given back)
        long refundsBefore = refunds(d2);
        mvc.perform(post(ApiPaths.Admin.DREAMS + "/" + d2 + "/cancel").header("Authorization", bearer(interpreter))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"duplicate\"}"))
                .andExpect(status().isNoContent());
        Dream cancelled = dreamRepository.findById(d2).orElseThrow();
        assertThat(cancelled.getStatus()).isEqualTo(DreamStatus.CANCELLED);
        assertThat(cancelled.getHeldOrderId()).isNull();
        assertThat(refunds(d2)).isEqualTo(refundsBefore);
        assertThat(creditService.balance(buyer.getId())).isZero();

        // approved again later: no dream waits on it, its 3 credits go to the balance
        submitTransfer(buyer, orderId, reference, null).andExpect(status().isOk());
        approve(interpreter, orderId, reference).andExpect(status().isOk());
        assertThat(creditService.balance(buyer.getId())).isEqualTo(3);
        assertThat(dreamStatus(d1)).isEqualTo(DreamStatus.IN_REVIEW);
    }

    // ================================================================== a dream paid with the order's spare credit

    @Test
    void aDreamSentWithTheOrdersSpareCreditIsHeldToo() throws Exception {
        User buyer = egyptian("revoke-spare");
        User interpreter = createUser("revoke-spare-interpreter", Role.INTERPRETER);
        UUID attached = createDraft(buyer);
        UUID orderId = checkout(buyer, PACKAGE_THREE, attached);
        String reference = uniqueReference();
        submitTransfer(buyer, orderId, reference, null).andExpect(status().isOk());
        approve(interpreter, orderId, reference).andExpect(status().isOk());
        assertThat(creditService.balance(buyer.getId())).isEqualTo(2);

        // a dream not in the payment, sent later with the balance: its credit comes from the same order
        UUID later = createDraft(buyer);
        submitDreams(buyer, later).andExpect(status().isOk());
        assertThat(ledgerRepository.findById(dreamRepository.findById(later).orElseThrow().getLedgerEntryId())
                .orElseThrow().getOrderId()).isEqualTo(orderId);
        assertThat(creditService.balance(buyer.getId())).isEqualTo(1);

        revoke(interpreter, orderId, null).andExpect(status().isOk()).andExpect(jsonPath("$.heldDreams").value(2));
        assertThat(dreamStatus(attached)).isEqualTo(DreamStatus.AWAITING_PAYMENT);
        assertThat(dreamStatus(later)).isEqualTo(DreamStatus.AWAITING_PAYMENT);
        assertThat(creditService.balance(buyer.getId())).isZero();

        submitTransfer(buyer, orderId, reference, null).andExpect(status().isOk());
        approve(interpreter, orderId, reference).andExpect(status().isOk());
        assertThat(dreamStatus(attached)).isEqualTo(DreamStatus.IN_REVIEW);
        assertThat(dreamStatus(later)).isEqualTo(DreamStatus.IN_REVIEW);
        assertThat(creditService.balance(buyer.getId())).isEqualTo(1);
    }

    // ================================================================== coupon counted once

    @Test
    void theCouponUseIsGivenBackAndCountedOnceAfterTheNextApproval() throws Exception {
        User buyer = egyptian("revoke-coupon");
        User interpreter = createUser("revoke-coupon-interpreter", Role.INTERPRETER);
        Coupon coupon = new Coupon();
        coupon.setCode("REVOKE" + (System.nanoTime() % 1_000_000));
        coupon.setType(CouponType.PERCENT);
        coupon.setValue(new BigDecimal("10"));
        coupon.setMaxUses(50);
        coupon = couponRepository.save(coupon);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("packageId", PACKAGE_ONE);
        body.put("couponCode", coupon.getCode());
        JsonNode checkout = json(mvc.perform(post(ApiPaths.Checkout.ROOT).header("Authorization", bearer(buyer))
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk()));
        UUID orderId = UUID.fromString(checkout.get("orderId").asText());
        String reference = uniqueReference();
        submitTransfer(buyer, orderId, reference, null).andExpect(status().isOk());
        approve(interpreter, orderId, reference).andExpect(status().isOk());
        assertThat(couponRepository.findById(coupon.getId()).orElseThrow().getUsedCount()).isEqualTo(1);
        assertThat(redemptionRepository.existsByOrderId(orderId)).isTrue();

        revoke(interpreter, orderId, null).andExpect(status().isOk());
        assertThat(couponRepository.findById(coupon.getId()).orElseThrow().getUsedCount()).isZero();
        // the redemption is kept as revoked: the same buyer cannot use the single-use coupon meanwhile
        assertThat(redemptionRepository.findByOrderId(orderId).orElseThrow().getRevokedAt()).isNotNull();
        mvc.perform(post(ApiPaths.Checkout.ROOT).header("Authorization", bearer(buyer))
                        .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)))
                .andExpect(jsonPath("$.code").value("INVALID_COUPON"));

        submitTransfer(buyer, orderId, reference, null).andExpect(status().isOk());
        approve(interpreter, orderId, reference).andExpect(status().isOk());
        assertThat(couponRepository.findById(coupon.getId()).orElseThrow().getUsedCount()).isEqualTo(1);
        assertThat(redemptionRepository.findByOrderId(orderId).orElseThrow().getRevokedAt()).isNull();
        assertThat(creditService.balance(buyer.getId())).isEqualTo(1);
    }

    @Test
    void aCouponUseThatWasNotCountedIsNotGivenBack() throws Exception {
        User buyer = egyptian("revoke-coupon-full");
        User interpreter = createUser("revoke-coupon-full-interpreter", Role.INTERPRETER);
        Coupon coupon = new Coupon();
        coupon.setCode("FULL" + (System.nanoTime() % 1_000_000));
        coupon.setType(CouponType.PERCENT);
        coupon.setValue(new BigDecimal("10"));
        coupon.setMaxUses(1);
        coupon = couponRepository.save(coupon);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("packageId", PACKAGE_ONE);
        body.put("couponCode", coupon.getCode());
        JsonNode checkout = json(mvc.perform(post(ApiPaths.Checkout.ROOT).header("Authorization", bearer(buyer))
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk()));
        UUID orderId = UUID.fromString(checkout.get("orderId").asText());
        // someone else used the last use meanwhile: this order's confirm cannot count one
        Coupon full = couponRepository.findById(coupon.getId()).orElseThrow();
        full.setUsedCount(1);
        couponRepository.save(full);

        String reference = uniqueReference();
        submitTransfer(buyer, orderId, reference, null).andExpect(status().isOk());
        approve(interpreter, orderId, reference).andExpect(status().isOk());
        assertThat(redemptionRepository.findByOrderId(orderId).orElseThrow().isCounted()).isFalse();
        revoke(interpreter, orderId, null).andExpect(status().isOk());
        assertThat(couponRepository.findById(coupon.getId()).orElseThrow().getUsedCount()).isEqualTo(1);
        submitTransfer(buyer, orderId, reference, null).andExpect(status().isOk());
        approve(interpreter, orderId, reference).andExpect(status().isOk());
        assertThat(couponRepository.findById(coupon.getId()).orElseThrow().getUsedCount()).isEqualTo(1);
    }

    // ================================================================== undo twice, exact ledger math

    @Test
    void aSecondUndoAfterAReapprovalAndAThirdApprovalAddUpExactly() throws Exception {
        User buyer = egyptian("revoke-twice");
        User interpreter = createUser("revoke-twice-interpreter", Role.INTERPRETER);
        UUID d1 = createDraft(buyer);
        UUID d2 = createDraft(buyer);
        UUID orderId = checkout(buyer, PACKAGE_THREE, d1, d2);
        String reference = uniqueReference();
        submitTransfer(buyer, orderId, reference, null).andExpect(status().isOk());
        approve(interpreter, orderId, reference).andExpect(status().isOk());
        assertThat(creditService.balance(buyer.getId())).isEqualTo(1);

        revoke(interpreter, orderId, "first").andExpect(status().isOk());
        assertThat(creditService.balance(buyer.getId())).isZero();
        awaitTrue("one e-mail for the first undo", () -> reviewMails(orderId) == 1);

        submitTransfer(buyer, orderId, reference, null).andExpect(status().isOk());
        approve(interpreter, orderId, reference).andExpect(status().isOk());
        assertThat(creditService.balance(buyer.getId())).isEqualTo(1);

        revoke(interpreter, orderId, "second").andExpect(status().isOk())
                .andExpect(jsonPath("$.heldDreams").value(2))
                .andExpect(jsonPath("$.revokeNote").value("second"));
        assertThat(creditService.balance(buyer.getId())).isZero();
        assertThat(dreamStatus(d1)).isEqualTo(DreamStatus.AWAITING_PAYMENT);
        assertThat(dreamStatus(d2)).isEqualTo(DreamStatus.AWAITING_PAYMENT);
        awaitTrue("a new e-mail for the second undo", () -> reviewMails(orderId) == 2);

        submitTransfer(buyer, orderId, reference, null).andExpect(status().isOk());
        approve(interpreter, orderId, reference).andExpect(status().isOk());
        assertThat(creditService.balance(buyer.getId())).isEqualTo(1);
        assertThat(dreamStatus(d1)).isEqualTo(DreamStatus.IN_REVIEW);
        assertThat(dreamStatus(d2)).isEqualTo(DreamStatus.IN_REVIEW);

        // 3 purchases (+9), 6 debits (-6), 4 refunds of the held debits (+4), 6 withdrawals (-6): balance 1
        List<CreditLedgerEntry> rows = ledgerRepository.findAllByOrderIdOrderByCreatedAtAsc(orderId);
        assertThat(count(rows, LedgerReason.PURCHASE)).isEqualTo(3);
        assertThat(count(rows, LedgerReason.SUBMIT)).isEqualTo(6);
        assertThat(count(rows, LedgerReason.REFUND)).isEqualTo(4);
        assertThat(count(rows, LedgerReason.REVOKE)).isEqualTo(6);
        assertThat(rows.stream().filter(e -> e.getReason() == LedgerReason.REVOKE).mapToInt(CreditLedgerEntry::getDelta)
                .sum()).isEqualTo(-6);
        assertThat(rows.stream().mapToInt(CreditLedgerEntry::getDelta).sum()).isEqualTo(1);
        List<CreditLedgerEntry> purchases = rows.stream().filter(e -> e.getReason() == LedgerReason.PURCHASE).toList();
        assertThat(purchases.get(0).getSourceId()).isNull();
        assertThat(purchases.get(1).getSourceId()).isEqualTo(purchases.get(0).getId());
        assertThat(purchases.get(2).getSourceId()).isEqualTo(purchases.get(1).getId());
        mvc.perform(get(ApiPaths.Admin.DREAMS + "/" + d2).header("Authorization", bearer(interpreter)))
                .andExpect(jsonPath("$.payment.credits").value(3))
                .andExpect(jsonPath("$.payment.creditsLeft").value(1));
        assertThat(reviewMails(orderId)).isEqualTo(2);
    }

    // ================================================================== expired credits are not revived

    @Test
    void aReapprovalAfterTheCreditsExpiredResumesTheHeldDreamButRevivesNothing() throws Exception {
        User buyer = egyptian("revoke-expired");
        User interpreter = createUser("revoke-expired-interpreter", Role.INTERPRETER);
        UUID d1 = createDraft(buyer);
        UUID orderId = checkout(buyer, PACKAGE_THREE, d1);
        String reference = uniqueReference();
        submitTransfer(buyer, orderId, reference, null).andExpect(status().isOk());
        approve(interpreter, orderId, reference).andExpect(status().isOk());
        assertThat(creditService.balance(buyer.getId())).isEqualTo(2);

        // the credits expire: the 2 left are written off
        CreditLedgerEntry first = ledgerRepository.findAllByOrderIdAndReasonOrderByCreatedAtAsc(orderId,
                LedgerReason.PURCHASE).get(0);
        Instant expiry = Instant.now().minus(Duration.ofDays(1));
        first.setExpiresAt(expiry);
        ledgerRepository.save(first);
        creditExpiryService.expireDueLocked(buyer.getId());
        assertThat(creditService.balance(buyer.getId())).isZero();

        revoke(interpreter, orderId, null).andExpect(status().isOk()).andExpect(jsonPath("$.heldDreams").value(1));
        assertThat(creditService.balance(buyer.getId())).isZero();
        assertThat(dreamStatus(d1)).isEqualTo(DreamStatus.AWAITING_PAYMENT);

        submitTransfer(buyer, orderId, reference, null).andExpect(status().isOk());
        approve(interpreter, orderId, reference).andExpect(status().isOk());
        assertThat(dreamStatus(d1)).isEqualTo(DreamStatus.IN_REVIEW);
        assertThat(creditService.balance(buyer.getId())).isZero();
        List<CreditLedgerEntry> purchases = ledgerRepository.findAllByOrderIdAndReasonOrderByCreatedAtAsc(orderId,
                LedgerReason.PURCHASE);
        assertThat(purchases).hasSize(2);
        assertThat(purchases.get(1).getExpiresAt()).isEqualTo(purchases.get(0).getExpiresAt());
        assertThat(purchases.get(1).getExpirySettledAt()).isNotNull();
        assertThat(ledgerRepository.findByUserIdOrderByCreatedAtAsc(buyer.getId()).stream()
                .filter(e -> e.getReason() == LedgerReason.EXPIRE).mapToInt(CreditLedgerEntry::getDelta).sum())
                .isEqualTo(-4);
    }

    // ================================================================== leftovers already spent

    @Test
    void anUndoAfterTheLeftoverCreditsWereSpentHoldsEveryDreamAndNeverGoesNegative() throws Exception {
        User buyer = egyptian("revoke-spent");
        User interpreter = createUser("revoke-spent-interpreter", Role.INTERPRETER);
        UUID d1 = createDraft(buyer);
        UUID orderId = checkout(buyer, PACKAGE_THREE, d1);
        String reference = uniqueReference();
        submitTransfer(buyer, orderId, reference, null).andExpect(status().isOk());
        approve(interpreter, orderId, reference).andExpect(status().isOk());
        UUID d2 = createDraft(buyer);
        UUID d3 = createDraft(buyer);
        submitDreams(buyer, d2, d3).andExpect(status().isOk()).andExpect(jsonPath("$.remainingCredits").value(0));
        // a gift that has nothing to do with the order stays
        creditService.add(buyer.getId(), 1, LedgerReason.BONUS, null, "gift", null);

        revoke(interpreter, orderId, null).andExpect(status().isOk()).andExpect(jsonPath("$.heldDreams").value(3));
        for (UUID id : List.of(d1, d2, d3)) {
            assertThat(dreamStatus(id)).isEqualTo(DreamStatus.AWAITING_PAYMENT);
        }
        assertThat(creditService.balance(buyer.getId())).isEqualTo(1);
        assertThat(ledgerRepository.findAllByOrderIdAndReasonOrderByCreatedAtAsc(orderId, LedgerReason.REVOKE)
                .stream().mapToInt(CreditLedgerEntry::getDelta).sum()).isEqualTo(-3);
        awaitTrue("PAYMENT_REVOKED", () -> notifications(buyer).stream()
                .anyMatch(n -> n.getType() == NotificationType.PAYMENT_REVOKED));
        assertThat(notifications(buyer).stream().filter(n -> n.getType() == NotificationType.PAYMENT_REVOKED)
                .findFirst().orElseThrow().getBody()).endsWith("رؤاك محفوظة.");
    }

    // ================================================================== an open question survives the hold

    @Test
    void aDreamHeldWhileWaitingForTheDreamersReplyComesBackWaitingForIt() throws Exception {
        User buyer = egyptian("revoke-question");
        User interpreter = createUser("revoke-question-interpreter", Role.INTERPRETER);
        UUID d1 = createDraft(buyer);
        UUID orderId = checkout(buyer, PACKAGE_ONE, d1);
        String reference = uniqueReference();
        submitTransfer(buyer, orderId, reference, null).andExpect(status().isOk());
        approve(interpreter, orderId, reference).andExpect(status().isOk());
        mvc.perform(post(ApiPaths.Admin.DREAMS + "/" + d1 + "/messages").header("Authorization", bearer(interpreter))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"هل البئر في بيتك؟\"}"))
                .andExpect(status().isCreated());
        Instant asked = dreamRepository.findById(d1).orElseThrow().getSlaPausedAt();
        assertThat(asked).isNotNull();

        revoke(interpreter, orderId, null).andExpect(status().isOk());
        assertThat(dreamStatus(d1)).isEqualTo(DreamStatus.AWAITING_PAYMENT);
        assertThat(dreamRepository.findById(d1).orElseThrow().getSlaPausedAt()).isEqualTo(asked);
        awaitTrue("PAYMENT_REVOKED", () -> notifications(buyer).stream()
                .anyMatch(n -> n.getType() == NotificationType.PAYMENT_REVOKED));
        assertThat(notifications(buyer).stream().filter(n -> n.getType() == NotificationType.PAYMENT_REVOKED)
                .findFirst().orElseThrow().getBody()).endsWith("رؤياك محفوظة.");
        // no reply while it waits for the payment
        reply(buyer, d1).andExpect(status().isConflict());

        submitTransfer(buyer, orderId, reference, null).andExpect(status().isOk());
        approve(interpreter, orderId, reference).andExpect(status().isOk());
        Dream back = dreamRepository.findById(d1).orElseThrow();
        assertThat(back.getStatus()).isEqualTo(DreamStatus.AWAITING_USER_REPLY);
        assertThat(back.getSlaPausedAt()).isEqualTo(asked);
        reply(buyer, d1).andExpect(status().isCreated());
        assertThat(dreamStatus(d1)).isEqualTo(DreamStatus.IN_REVIEW);
    }

    // ================================================================== the reference stays reserved

    @Test
    void theReferenceOfARevokedOrderCannotBeTakenByAnotherOrder() throws Exception {
        User buyer = egyptian("revoke-ref");
        User other = egyptian("revoke-ref-other");
        User interpreter = createUser("revoke-ref-interpreter", Role.INTERPRETER);
        UUID orderId = checkout(buyer, PACKAGE_ONE);
        String reference = uniqueReference();
        submitTransfer(buyer, orderId, reference, null).andExpect(status().isOk());
        approve(interpreter, orderId, reference).andExpect(status().isOk());
        revoke(interpreter, orderId, null).andExpect(status().isOk());

        UUID otherOrder = checkout(other, PACKAGE_ONE);
        submitTransfer(other, otherOrder, reference, null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_REFERENCE"));

        // freed once the buyer sends another number
        submitTransfer(buyer, orderId, uniqueReference(), null).andExpect(status().isOk());
        submitTransfer(other, otherOrder, reference, null).andExpect(status().isOk());
    }

    // ================================================================== helpers

    private long reviewMails(UUID orderId) {
        return emailLogRepository.findByTemplateOrderByCreatedAtDesc(MailTemplates.PAYMENT_REVIEW_NEEDED).stream()
                .filter(m -> m.getRef() != null && m.getRef().startsWith(MailTemplates.PAYMENT_REVIEW_NEEDED + ":" + orderId))
                .count();
    }

    private static long count(List<CreditLedgerEntry> rows, LedgerReason reason) {
        return rows.stream().filter(e -> e.getReason() == reason).count();
    }

    private ResultActions reply(User user, UUID id) throws Exception {
        return mvc.perform(post(ApiPaths.Dreams.ROOT + "/" + id + "/messages").header("Authorization", bearer(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"نعم، في بيت جدي.\"}"));
    }

    private long refunds(UUID dreamId) {
        return ledgerRepository.findAll().stream()
                .filter(e -> e.getReason() == LedgerReason.REFUND && dreamId.equals(e.getDreamId()))
                .count();
    }

    private DreamStatus dreamStatus(UUID dreamId) {
        return dreamRepository.findById(dreamId).orElseThrow().getStatus();
    }

    private JsonNode summary(User interpreter) throws Exception {
        return json(mvc.perform(get(ApiPaths.Admin.ANALYTICS_SUMMARY).header("Authorization", bearer(interpreter)))
                .andExpect(status().isOk()));
    }

    private List<Notification> notifications(User user) {
        return notificationRepository.findByUserIdOrderByCreatedAtDesc(user.getId(), Pageable.unpaged()).getContent();
    }

    private User egyptian(String name) {
        User user = createUser(name, Role.USER);
        user.setCountryCode("EG");
        return userRepository.save(user);
    }

    private ResultActions start(User interpreter, UUID id, int days) throws Exception {
        return mvc.perform(post(ApiPaths.Admin.DREAMS + "/" + id + "/start").header("Authorization", bearer(interpreter))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedDays\":" + days + "}"));
    }

    private ResultActions submitDreams(User user, UUID... ids) throws Exception {
        return mvc.perform(post(ApiPaths.Dreams.SUBMIT).header("Authorization", bearer(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("dreamIds",
                        java.util.Arrays.stream(ids).map(UUID::toString).toList()))));
    }

    private List<UUID> listIds(User user, String status) throws Exception {
        JsonNode page = json(mvc.perform(get(ApiPaths.Dreams.ROOT).param("status", status).param("size", "100")
                .header("Authorization", bearer(user))).andExpect(status().isOk()));
        List<UUID> ids = new ArrayList<>();
        page.get("items").forEach(i -> ids.add(UUID.fromString(i.get("id").asText())));
        return ids;
    }

    private UUID checkout(User user, String packageId, UUID... drafts) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("packageId", packageId);
        if (drafts.length > 0) {
            body.put("dreamIds", java.util.Arrays.stream(drafts).map(UUID::toString).toList());
        }
        JsonNode response = json(mvc.perform(post(ApiPaths.Checkout.ROOT).header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk()));
        return UUID.fromString(response.get("orderId").asText());
    }

    private ResultActions submitTransfer(User user, UUID orderId, String reference, List<UUID> dreamIds)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reference", reference);
        body.put("senderName", "BUYER NAME");
        if (dreamIds != null) {
            body.put("dreamIds", dreamIds.stream().map(UUID::toString).toList());
        }
        return mvc.perform(post(ApiPaths.Me.ORDERS + "/" + orderId + "/instapay").header("Authorization", bearer(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions approve(User interpreter, UUID orderId, String reference) throws Exception {
        return mvc.perform(post(ApiPaths.Admin.INSTAPAY_TRANSFERS + "/" + orderId + "/approve")
                .header("Authorization", bearer(interpreter)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reference\":\"" + reference + "\"}"));
    }

    private ResultActions reject(User interpreter, UUID orderId, String note) throws Exception {
        return mvc.perform(post(ApiPaths.Admin.INSTAPAY_TRANSFERS + "/" + orderId + "/reject")
                .header("Authorization", bearer(interpreter)).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("note", note))));
    }

    private ResultActions revoke(User user, UUID orderId, String note) throws Exception {
        return mvc.perform(post(ApiPaths.Admin.INSTAPAY_TRANSFERS + "/" + orderId + "/revoke")
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
