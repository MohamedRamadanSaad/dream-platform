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
import com.saadat.common.domain.CouponType;
import com.saadat.common.domain.DreamStatus;
import com.saadat.common.domain.LedgerReason;
import com.saadat.common.domain.NotificationType;
import com.saadat.common.domain.OrderStatus;
import com.saadat.common.domain.PaymentProviderType;
import com.saadat.common.domain.Role;
import com.saadat.credits.service.CreditService;
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
        assertThat(n.getBody()).contains("إغلاق الحساب").doesNotContain("{");
        awaitTrue("payment-review-needed e-mail", () -> emailLogRepository.existsByTemplateAndRefStartingWith(
                MailTemplates.PAYMENT_REVIEW_NEEDED, MailTemplates.PAYMENT_REVIEW_NEEDED + ":" + orderId));
        assertThat(auditLogRepository.findByEntityAndEntityIdOrderByCreatedAtDesc("orders", orderId.toString()))
                .anyMatch(a -> "INSTAPAY_REVOKED".equals(a.getAction()));

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
        assertThat(redemptionRepository.existsByOrderId(orderId)).isFalse();

        submitTransfer(buyer, orderId, reference, null).andExpect(status().isOk());
        approve(interpreter, orderId, reference).andExpect(status().isOk());
        assertThat(couponRepository.findById(coupon.getId()).orElseThrow().getUsedCount()).isEqualTo(1);
        assertThat(redemptionRepository.existsByOrderId(orderId)).isTrue();
        assertThat(creditService.balance(buyer.getId())).isEqualTo(1);
    }

    // ================================================================== helpers

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
