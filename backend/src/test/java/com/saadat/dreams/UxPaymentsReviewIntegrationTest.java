package com.saadat.dreams;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import com.saadat.common.domain.NotificationType;
import com.saadat.common.domain.OrderStatus;
import com.saadat.common.domain.Role;
import com.saadat.credits.service.CreditService;
import com.saadat.dreams.domain.Dream;
import com.saadat.dreams.repo.DreamRepository;
import com.saadat.dreams.service.DreamService;
import com.saadat.mail.MailTemplates;
import com.saadat.notifications.domain.Notification;
import com.saadat.notifications.repo.EmailLogRepository;
import com.saadat.notifications.repo.NotificationRepository;
import com.saadat.payments.domain.Order;
import com.saadat.payments.repo.OrderRepository;
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
 * docs/UX_PAYMENTS_REVIEW_CONTRACT.md: "Start interpreting" (IN_PROGRESS), edits before it, drafts attached to a
 * payment, the interpreter's order tabs and order page, settled EGP and the country dashboard ranked in EGP.
 */
class UxPaymentsReviewIntegrationTest extends IntegrationTestBase {

    /** Seeded package p1 (1 credit). */
    private static final String PACKAGE_ONE = "22222222-2222-4222-8222-000000000001";
    /** Seeded package p3 (3 credits). */
    private static final String PACKAGE_THREE = "22222222-2222-4222-8222-000000000003";
    private static final String DREAM_TEXT = "رأيت أني أقف على باب بيت قديم وفي يدي مفتاح من نحاس يلمع في الشمس.";
    private static final String EDITED_TEXT = "رأيت أني أقف على باب بيت قديم وفي يدي مفتاحان من نحاس يلمعان في الشمس.";

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    CreditService creditService;

    @Autowired
    DreamService dreamService;

    @Autowired
    DreamRepository dreamRepository;

    @Autowired
    OrderRepository orderRepository;

    @Autowired
    NotificationRepository notificationRepository;

    @Autowired
    EmailLogRepository emailLogRepository;

    // ================================================================== §1 start interpreting

    @Test
    void theDreamerEditsUntilTheInterpreterStartsThenTheTextIsFinal() throws Exception {
        User user = createUser("start-user", Role.USER);
        User interpreter = createUser("start-interpreter", Role.INTERPRETER);
        creditService.add(user.getId(), 1, LedgerReason.BONUS, null, "test", null);
        UUID id = createDraft(user);
        dreamService.submit(user.getId(), List.of(id), null);

        // IN_REVIEW and not started: editable, the interpreter sees "edited by the dreamer"
        mvc.perform(get(ApiPaths.Dreams.ROOT + "/" + id).header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.editable").value(true))
                .andExpect(jsonPath("$.startedAt").isEmpty())
                .andExpect(jsonPath("$.payment").isEmpty());
        edit(user, id, EDITED_TEXT)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value(EDITED_TEXT))
                .andExpect(jsonPath("$.status").value("IN_REVIEW"));
        mvc.perform(get(ApiPaths.Admin.DREAMS + "/" + id).header("Authorization", bearer(interpreter)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userEditedAt").isNotEmpty())
                .andExpect(jsonPath("$.text").value(EDITED_TEXT));

        // bad day counts are refused
        start(interpreter, id, 0).andExpect(status().isBadRequest());
        start(interpreter, id, 31).andExpect(status().isBadRequest());

        Instant before = Instant.now();
        start(interpreter, id, 3)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.expectedDays").value(3))
                .andExpect(jsonPath("$.startedAt").isNotEmpty())
                .andExpect(jsonPath("$.editable").value(false))
                .andExpect(jsonPath("$.user.id").value(user.getId().toString()));
        Dream started = dreamRepository.findById(id).orElseThrow();
        assertThat(started.getStatus()).isEqualTo(DreamStatus.IN_PROGRESS);
        assertThat(started.getExpectedBy()).isBetween(before.plus(Duration.ofDays(3)).minusSeconds(5),
                Instant.now().plus(Duration.ofDays(3)).plusSeconds(5));

        // a second start is refused; the text is final now
        start(interpreter, id, 2)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_IN_REVIEW"));
        edit(user, id, DREAM_TEXT)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_EDITABLE"));

        // the dreamer is told (in-app + push + e-mail) with the number of days
        awaitTrue("DREAM_STARTED notification", () -> notifications(user).stream()
                .anyMatch(n -> n.getType() == NotificationType.DREAM_STARTED));
        Notification n = notifications(user).stream().filter(x -> x.getType() == NotificationType.DREAM_STARTED)
                .findFirst().orElseThrow();
        assertThat(n.getBody()).contains("3 أيام").contains("بدأت المعبّرة تعبير رؤياك").doesNotContain("{");
        assertThat(n.getLink()).isEqualTo("/me/dreams/" + id);
        awaitTrue("dream-started e-mail", () -> emailLogRepository.existsByTemplateAndRef(MailTemplates.DREAM_STARTED,
                MailTemplates.DREAM_STARTED + ":" + id));

        // the queue tab and the dashboard count it; it is "open work"
        mvc.perform(get(ApiPaths.Admin.DREAMS).param("status", "IN_PROGRESS").param("size", "100")
                        .header("Authorization", bearer(interpreter)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.id == '" + id + "')].expectedDays").value(org.hamcrest.Matchers.hasItem(3)));
        mvc.perform(get(ApiPaths.Admin.ANALYTICS_SUMMARY).header("Authorization", bearer(interpreter)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inProgress").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.pendingPayments").isNumber())
                .andExpect(jsonPath("$.revenueEgp").isNumber());
        mvc.perform(get(ApiPaths.Me.DASHBOARD).header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inProgress").value(1))
                .andExpect(jsonPath("$.inReview").value(0))
                .andExpect(jsonPath("$.pendingPayment").value(0));

        // question → reply goes back to IN_PROGRESS (not IN_REVIEW) because it was started
        mvc.perform(post(ApiPaths.Admin.DREAMS + "/" + id + "/messages").header("Authorization", bearer(interpreter))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"هل البيت القديم هو بيت أهلك؟\"}"))
                .andExpect(status().isCreated());
        assertThat(dreamRepository.findById(id).orElseThrow().getStatus()).isEqualTo(DreamStatus.AWAITING_USER_REPLY);
        mvc.perform(post(ApiPaths.Dreams.ROOT + "/" + id + "/messages").header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"نعم، بيت جدي.\"}"))
                .andExpect(status().isCreated());
        assertThat(dreamRepository.findById(id).orElseThrow().getStatus()).isEqualTo(DreamStatus.IN_PROGRESS);

        // interpretation from IN_PROGRESS
        mvc.perform(post(ApiPaths.Admin.DREAMS + "/" + id + "/interpretation").header("Authorization", bearer(interpreter))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"رؤيا خير والله أعلم.\"}"))
                .andExpect(status().isNoContent());
        assertThat(dreamRepository.findById(id).orElseThrow().getStatus()).isEqualTo(DreamStatus.INTERPRETED);
    }

    @Test
    void aStartedDreamCanBeCancelledAndIsOverdueAfterItsExpectedDays() throws Exception {
        User user = createUser("start-cancel", Role.USER);
        User interpreter = createUser("start-cancel-interpreter", Role.INTERPRETER);
        creditService.add(user.getId(), 1, LedgerReason.BONUS, null, "test", null);
        UUID id = createDraft(user);
        dreamService.submit(user.getId(), List.of(id), null);
        start(interpreter, id, 1).andExpect(status().isOk());

        Dream d = dreamRepository.findById(id).orElseThrow();
        d.setExpectedBy(Instant.now().minus(Duration.ofHours(1)));
        dreamRepository.save(d);
        mvc.perform(get(ApiPaths.Admin.DREAMS).param("status", "IN_PROGRESS").param("size", "100")
                        .header("Authorization", bearer(interpreter)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.id == '" + id + "')].overdue").value(org.hamcrest.Matchers.hasItem(true)));

        mvc.perform(post(ApiPaths.Admin.DREAMS + "/" + id + "/cancel").header("Authorization", bearer(interpreter))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"duplicate\"}"))
                .andExpect(status().isNoContent());
        assertThat(dreamRepository.findById(id).orElseThrow().getStatus()).isEqualTo(DreamStatus.CANCELLED);
        assertThat(creditService.balance(user.getId())).isEqualTo(1);
    }

    // ================================================================== §2 attached dreams

    @Test
    void aDraftAttachedToAPaymentLeavesTheDraftsAndIsSentWhenTheTransferIsAccepted() throws Exception {
        User buyer = egyptian("attach-buyer");
        User interpreter = createUser("attach-interpreter", Role.INTERPRETER);
        UUID attached = createDraft(buyer);
        UUID loose = createDraft(buyer);

        UUID orderId = checkout(buyer, PACKAGE_THREE, attached).andExpect(status().isOk()).andReturnOrderId();
        // INITIATED: not attached yet (the buyer has not sent the transfer), but already held by the order
        assertThat(listIds(buyer, "DRAFT")).contains(attached, loose);
        checkout(buyer, attached).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DREAM_ALREADY_ATTACHED"));
        String reference = uniqueReference();
        submitTransfer(buyer, orderId, reference, null).andExpect(status().isOk());

        assertThat(listIds(buyer, "DRAFT")).contains(loose).doesNotContain(attached);
        assertThat(listIds(buyer, "PENDING_PAYMENT")).containsExactly(attached);
        mvc.perform(get(ApiPaths.Dreams.ROOT).param("status", "PENDING_PAYMENT").header("Authorization", bearer(buyer)))
                .andExpect(jsonPath("$.items[0].payment.orderId").value(orderId.toString()))
                .andExpect(jsonPath("$.items[0].payment.status").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.items[0].payment.submittedAt").isNotEmpty())
                .andExpect(jsonPath("$.items[0].editable").value(true));
        mvc.perform(get(ApiPaths.Dreams.ROOT + "/" + attached).header("Authorization", bearer(buyer)))
                .andExpect(jsonPath("$.payment.status").value("PENDING_REVIEW"));
        mvc.perform(get(ApiPaths.Me.DASHBOARD).header("Authorization", bearer(buyer)))
                .andExpect(jsonPath("$.drafts").value(1))
                .andExpect(jsonPath("$.pendingPayment").value(1));

        // it cannot be deleted nor paid by another order
        mvc.perform(delete(ApiPaths.Dreams.ROOT + "/" + attached).header("Authorization", bearer(buyer)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ATTACHED_TO_PAYMENT"));
        checkout(buyer, attached).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DREAM_ALREADY_ATTACHED"));
        UUID other = checkout(buyer, null).andExpect(status().isOk()).andReturnOrderId();
        submitTransfer(buyer, other, uniqueReference(), List.of(attached))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DREAM_ALREADY_ATTACHED"));
        // the same order may be corrected with its own dream (and a new one)
        submitTransfer(buyer, orderId, reference, List.of(attached, loose))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attachedDreams").value(2));
        assertThat(listIds(buyer, "PENDING_PAYMENT")).containsExactlyInAnyOrder(attached, loose);

        // rejected: still attached, shown as REJECTED with the note
        reject(interpreter, orderId, "No transfer with this number").andExpect(status().isOk());
        mvc.perform(get(ApiPaths.Dreams.ROOT).param("status", "PENDING_PAYMENT").header("Authorization", bearer(buyer)))
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[0].payment.status").value("REJECTED"))
                .andExpect(jsonPath("$.items[0].payment.reviewNote").value("No transfer with this number"));

        // resent and accepted: both dreams go to the interpreter
        String corrected = uniqueReference();
        submitTransfer(buyer, orderId, corrected, null).andExpect(status().isOk());
        approve(interpreter, orderId, corrected).andExpect(status().isOk());
        assertThat(dreamRepository.findById(attached).orElseThrow().getStatus()).isEqualTo(DreamStatus.IN_REVIEW);
        assertThat(listIds(buyer, "PENDING_PAYMENT")).isEmpty();
        assertThat(listIds(buyer, "IN_REVIEW")).contains(attached);
    }

    @Test
    void aRejectedPaymentNeverTrapsItsDreams() throws Exception {
        User buyer = egyptian("rejected-buyer");
        User interpreter = createUser("rejected-interpreter", Role.INTERPRETER);
        UUID d1 = createDraft(buyer);
        UUID d2 = createDraft(buyer);
        UUID d3 = createDraft(buyer);
        UUID orderA = checkout(buyer, PACKAGE_THREE, d1, d2, d3).andExpect(status().isOk()).andReturnOrderId();
        submitTransfer(buyer, orderA, uniqueReference(), null).andExpect(status().isOk());
        reject(interpreter, orderA, "Not found").andExpect(status().isOk());

        // still shown under the payment, as REJECTED
        assertThat(listIds(buyer, "PENDING_PAYMENT")).containsExactlyInAnyOrder(d1, d2, d3);
        mvc.perform(get(ApiPaths.Dreams.ROOT + "/" + d1).header("Authorization", bearer(buyer)))
                .andExpect(jsonPath("$.payment.status").value("REJECTED"))
                .andExpect(jsonPath("$.payment.orderId").value(orderA.toString()));

        // deleted: it leaves the rejected order
        mvc.perform(delete(ApiPaths.Dreams.ROOT + "/" + d1).header("Authorization", bearer(buyer)))
                .andExpect(status().isNoContent());
        assertThat(intentOf(orderA)).containsExactlyInAnyOrder(d2, d3);

        // submitted with credits the user already has: it leaves the rejected order
        creditService.add(buyer.getId(), 1, LedgerReason.BONUS, null, "test", null);
        mvc.perform(post(ApiPaths.Dreams.SUBMIT).header("Authorization", bearer(buyer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dreamIds\":[\"" + d2 + "\"]}"))
                .andExpect(status().isOk());
        assertThat(dreamRepository.findById(d2).orElseThrow().getStatus()).isEqualTo(DreamStatus.IN_REVIEW);
        assertThat(intentOf(orderA)).containsExactly(d3);

        // attached to a new checkout: it leaves the rejected order and is a plain draft again (new order INITIATED)
        UUID orderC = checkout(buyer, PACKAGE_ONE, d3).andExpect(status().isOk()).andReturnOrderId();
        assertThat(intentOf(orderA)).isEmpty();
        assertThat(intentOf(orderC)).containsExactly(d3);
        assertThat(listIds(buyer, "PENDING_PAYMENT")).isEmpty();
        assertThat(listIds(buyer, "DRAFT")).contains(d3);

        // the rejected order can still be resent (now without dreams)
        submitTransfer(buyer, orderA, uniqueReference(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.attachedDreams").value(0));

        // attached through another InstaPay order: it leaves the rejected one
        UUID d4 = createDraft(buyer);
        UUID orderE = checkout(buyer, PACKAGE_ONE, d4).andExpect(status().isOk()).andReturnOrderId();
        submitTransfer(buyer, orderE, uniqueReference(), null).andExpect(status().isOk());
        reject(interpreter, orderE, "Not found").andExpect(status().isOk());
        UUID orderF = checkout(buyer, PACKAGE_ONE).andExpect(status().isOk()).andReturnOrderId();
        submitTransfer(buyer, orderF, uniqueReference(), List.of(d4))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attachedDreams").value(1));
        assertThat(intentOf(orderE)).isEmpty();
        mvc.perform(get(ApiPaths.Dreams.ROOT + "/" + d4).header("Authorization", bearer(buyer)))
                .andExpect(jsonPath("$.payment.orderId").value(orderF.toString()))
                .andExpect(jsonPath("$.payment.status").value("PENDING_REVIEW"));
    }

    @Test
    void theBuyerReleasesTheDreamsOfAnOrder() throws Exception {
        User buyer = egyptian("release-buyer");
        User interpreter = createUser("release-interpreter", Role.INTERPRETER);
        User stranger = egyptian("release-stranger");
        UUID d1 = createDraft(buyer);
        UUID d2 = createDraft(buyer);
        UUID orderId = checkout(buyer, PACKAGE_THREE, d1, d2).andExpect(status().isOk()).andReturnOrderId();

        release(stranger, orderId, null).andExpect(status().isNotFound());
        // INITIATED: one dream
        release(buyer, orderId, List.of(d1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.released[0]").value(d1.toString()));
        assertThat(intentOf(orderId)).containsExactly(d2);
        mvc.perform(delete(ApiPaths.Dreams.ROOT + "/" + d1).header("Authorization", bearer(buyer)))
                .andExpect(status().isNoContent());

        // PENDING_REVIEW: held until released (no body = all)
        String reference = uniqueReference();
        submitTransfer(buyer, orderId, reference, null).andExpect(status().isOk());
        assertThat(listIds(buyer, "PENDING_PAYMENT")).containsExactly(d2);
        mvc.perform(delete(ApiPaths.Dreams.ROOT + "/" + d2).header("Authorization", bearer(buyer)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ATTACHED_TO_PAYMENT"))
                .andExpect(jsonPath("$.orderId").value(orderId.toString()));
        release(buyer, orderId, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.released.length()").value(1));
        assertThat(listIds(buyer, "PENDING_PAYMENT")).isEmpty();
        assertThat(listIds(buyer, "DRAFT")).contains(d2);

        // accepted: a second approval is refused, the dreams can no longer be released
        approve(interpreter, orderId, reference).andExpect(status().isOk());
        approve(interpreter, orderId, reference)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_PENDING"));
        release(buyer, orderId, null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_RELEASABLE"));

        // expired: not releasable either
        UUID expired = checkout(buyer, PACKAGE_ONE, d2).andExpect(status().isOk()).andReturnOrderId();
        Order o = orderRepository.findById(expired).orElseThrow();
        o.setStatus(OrderStatus.EXPIRED);
        orderRepository.save(o);
        release(buyer, expired, null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_RELEASABLE"));
    }

    @Test
    void anOrderNeverCarriesMoreDreamsThanItsCredits() throws Exception {
        User buyer = egyptian("too-many");
        UUID d1 = createDraft(buyer);
        UUID d2 = createDraft(buyer);

        checkout(buyer, PACKAGE_ONE, d1, d2)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TOO_MANY_DREAMS"));
        UUID orderId = checkout(buyer, PACKAGE_ONE, d1).andExpect(status().isOk()).andReturnOrderId();
        submitTransfer(buyer, orderId, uniqueReference(), List.of(d2))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TOO_MANY_DREAMS"));
        Order unchanged = orderRepository.findById(orderId).orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo(OrderStatus.INITIATED);
        assertThat(intentOf(orderId)).containsExactly(d1);
        // the same dream again is fine
        submitTransfer(buyer, orderId, uniqueReference(), List.of(d1)).andExpect(status().isOk());
    }

    @Test
    void editingAndStartingFollowTheStatus() throws Exception {
        User user = createUser("rules-user", Role.USER);
        User interpreter = createUser("rules-interpreter", Role.INTERPRETER);
        creditService.add(user.getId(), 1, LedgerReason.BONUS, null, "test", null);
        UUID id = createDraft(user);
        dreamService.submit(user.getId(), List.of(id), null);

        // only the interpreter starts
        start(user, id, 3).andExpect(status().isForbidden());

        // question before the start: the dreamer may still edit; the interpreter is told once per hour
        ask(interpreter, id).andExpect(status().isCreated());
        mvc.perform(get(ApiPaths.Dreams.ROOT + "/" + id).header("Authorization", bearer(user)))
                .andExpect(jsonPath("$.status").value("AWAITING_USER_REPLY"))
                .andExpect(jsonPath("$.editable").value(true));
        edit(user, id, EDITED_TEXT).andExpect(status().isOk());
        awaitTrue("DREAM_EDITED", () -> editedNotices(interpreter, id) == 1);
        edit(user, id, DREAM_TEXT).andExpect(status().isOk());
        assertThat(editedNotices(interpreter, id)).isEqualTo(1);
        assertThat(dreamRepository.findById(id).orElseThrow().getUserEditedAt()).isNotNull();
        // an hour later another edit notifies again
        Dream d = dreamRepository.findById(id).orElseThrow();
        d.setUserEditedAt(Instant.now().minus(Duration.ofMinutes(61)));
        dreamRepository.save(d);
        edit(user, id, EDITED_TEXT).andExpect(status().isOk());
        awaitTrue("second DREAM_EDITED", () -> editedNotices(interpreter, id) == 2);

        // no start while waiting for the dreamer; the reply of a never-started dream goes back to IN_REVIEW
        start(interpreter, id, 3)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_IN_REVIEW"));
        reply(user, id).andExpect(status().isCreated());
        assertThat(dreamRepository.findById(id).orElseThrow().getStatus()).isEqualTo(DreamStatus.IN_REVIEW);

        // after the start the text is final, also while waiting for a reply
        start(interpreter, id, 2).andExpect(status().isOk());
        ask(interpreter, id).andExpect(status().isCreated());
        mvc.perform(get(ApiPaths.Dreams.ROOT + "/" + id).header("Authorization", bearer(user)))
                .andExpect(jsonPath("$.status").value("AWAITING_USER_REPLY"))
                .andExpect(jsonPath("$.editable").value(false));
        edit(user, id, DREAM_TEXT)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_EDITABLE"));
    }

    @Test
    void ordersWithoutACountryAreInternationalAndTheReviewTabIsOldestFirst() throws Exception {
        User interpreter = createUser("tabs-interpreter", Role.INTERPRETER);
        User buyer = egyptian("tabs-buyer");
        UUID first = checkout(buyer, PACKAGE_ONE).andExpect(status().isOk()).andReturnOrderId();
        UUID second = checkout(buyer, PACKAGE_ONE).andExpect(status().isOk()).andReturnOrderId();
        submitTransfer(buyer, first, uniqueReference(), null).andExpect(status().isOk());
        submitTransfer(buyer, second, uniqueReference(), null).andExpect(status().isOk());
        // the second transfer was sent earlier
        Order a = orderRepository.findById(first).orElseThrow();
        a.setTransferSubmittedAt(Instant.parse("2001-01-02T00:00:00Z"));
        orderRepository.save(a);
        Order b = orderRepository.findById(second).orElseThrow();
        b.setTransferSubmittedAt(Instant.parse("2001-01-01T00:00:00Z"));
        orderRepository.save(b);
        List<String> review = orderIds(interpreter, "REVIEW");
        assertThat(review.indexOf(second.toString())).isNotNegative().isLessThan(review.indexOf(first.toString()));

        User nowhere = createUser("tabs-nowhere", Role.USER);
        UUID noCountry = checkout(nowhere, PACKAGE_ONE).andExpect(status().isOk()).andReturnOrderId();
        Order n = orderRepository.findById(noCountry).orElseThrow();
        n.setCountryCode(null);
        // an unpaid booking lives in the UNPAID tab (V41): a failed order shows where its country puts it
        n.setStatus(OrderStatus.FAILED);
        n.setFailureReason("TRANSFER_REJECTED"); // a gateway failure would be an unpaid booking
        orderRepository.save(n);
        assertThat(orderIds(interpreter, "INTERNATIONAL")).contains(noCountry.toString());
        assertThat(orderIds(interpreter, "DOMESTIC")).doesNotContain(noCountry.toString());
    }

    // ================================================================== §3 admin orders

    @Test
    void ordersTabsCountsAndTheOrderPage() throws Exception {
        User buyer = egyptian("orders-buyer");
        User interpreter = createUser("orders-interpreter", Role.INTERPRETER);
        UUID draft = createDraft(buyer);
        UUID orderId = checkout(buyer, draft).andExpect(status().isOk()).andReturnOrderId();
        String reference = uniqueReference();
        submitTransfer(buyer, orderId, reference, null).andExpect(status().isOk());

        User foreign = createUser("orders-foreign", Role.USER); // SA → USD through the mock gateway
        UUID foreignOrder = checkout(foreign, null).andExpect(status().isOk()).andReturnOrderId();
        // paid (an unpaid booking would be in the UNPAID tab, V41)
        mvc.perform(post(ApiPaths.Webhooks.ROOT + "/mock/" + foreignOrder).param("success", "true"))
                .andExpect(status().isOk());

        JsonNode counts = json(mvc.perform(get(ApiPaths.Admin.ORDER_COUNTS).header("Authorization", bearer(interpreter)))
                .andExpect(status().isOk()));
        assertThat(counts.get("review").asLong()).isPositive();
        assertThat(counts.get("domestic").asLong()).isPositive();
        assertThat(counts.get("international").asLong()).isPositive();

        List<String> review = orderIds(interpreter, "REVIEW");
        assertThat(review).contains(orderId.toString()).doesNotContain(foreignOrder.toString());
        assertThat(orderIds(interpreter, "DOMESTIC")).contains(orderId.toString()).doesNotContain(foreignOrder.toString());
        assertThat(orderIds(interpreter, "INTERNATIONAL")).contains(foreignOrder.toString()).doesNotContain(orderId.toString());
        assertThat(orderIds(interpreter, "ALL")).contains(orderId.toString(), foreignOrder.toString());

        // the order-number search works inside a tab: ?scope=REVIEW&q=15001
        String number = orderRepository.findById(orderId).orElseThrow().getOrderNumber();
        String digits = number.substring(number.lastIndexOf('-') + 1);
        for (String q : List.of(digits, number.toLowerCase(java.util.Locale.ROOT))) {
            mvc.perform(get(ApiPaths.Admin.ORDERS).param("scope", "REVIEW").param("q", q)
                            .header("Authorization", bearer(interpreter)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(1))
                    .andExpect(jsonPath("$.items[0].id").value(orderId.toString()))
                    .andExpect(jsonPath("$.items[0].orderNumber").value(number));
        }
        mvc.perform(get(ApiPaths.Admin.ORDERS).param("scope", "INTERNATIONAL").param("q", digits)
                        .header("Authorization", bearer(interpreter)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
        // no scope = ALL (CSV export)
        mvc.perform(get(ApiPaths.Admin.ORDERS).param("size", "100").header("Authorization", bearer(interpreter)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.id == '" + orderId + "')].transferReference")
                        .value(org.hamcrest.Matchers.hasItem(reference)))
                .andExpect(jsonPath("$.items[?(@.id == '" + orderId + "')].attachedDreams")
                        .value(org.hamcrest.Matchers.hasItem(1)))
                .andExpect(jsonPath("$.items[?(@.id == '" + orderId + "')].userEmail")
                        .value(org.hamcrest.Matchers.hasItem(buyer.getEmail())));

        mvc.perform(get(ApiPaths.Admin.ORDERS + "/" + orderId).header("Authorization", bearer(interpreter)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.orderNumber").value(number))
                .andExpect(jsonPath("$.provider").value("INSTAPAY"))
                .andExpect(jsonPath("$.canReview").value(true))
                .andExpect(jsonPath("$.transfer.reference").value(reference))
                .andExpect(jsonPath("$.transfer.reviewedByName").isEmpty())
                .andExpect(jsonPath("$.user.email").value(buyer.getEmail()))
                .andExpect(jsonPath("$.dreams[0].id").value(draft.toString()))
                .andExpect(jsonPath("$.dreams[0].text").value(DREAM_TEXT))
                .andExpect(jsonPath("$.dreams[0].status").value("DRAFT"))
                .andExpect(jsonPath("$.settledAmountEgp").isEmpty());

        approve(interpreter, orderId, reference).andExpect(status().isOk());
        mvc.perform(get(ApiPaths.Admin.ORDERS + "/" + orderId).header("Authorization", bearer(interpreter)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.canReview").value(false))
                .andExpect(jsonPath("$.transfer.reviewedByName").value(interpreter.getName()))
                .andExpect(jsonPath("$.dreams.length()").value(1))
                .andExpect(jsonPath("$.dreams[0].status").value("IN_REVIEW"))
                .andExpect(jsonPath("$.settledAmountEgp").isNumber());
        assertThat(orderIds(interpreter, "REVIEW")).doesNotContain(orderId.toString());

        mvc.perform(get(ApiPaths.Admin.ORDERS + "/" + UUID.randomUUID()).header("Authorization", bearer(interpreter)))
                .andExpect(status().isNotFound());
        mvc.perform(get(ApiPaths.Admin.ORDER_COUNTS).header("Authorization", bearer(buyer)))
                .andExpect(status().isForbidden());
    }

    // ================================================================== §4 settled EGP + §5 analytics

    @Test
    void settledEgpIsSetOnConfirmAndRevenueIsRankedInEgp() throws Exception {
        User interpreter = createUser("egp-interpreter", Role.INTERPRETER);
        // EGP order through InstaPay: settled = amount
        User egyptian = egyptian("egp-buyer");
        UUID egpOrder = checkout(egyptian, null).andExpect(status().isOk()).andReturnOrderId();
        String reference = uniqueReference();
        submitTransfer(egyptian, egpOrder, reference, null).andExpect(status().isOk());
        approve(interpreter, egpOrder, reference).andExpect(status().isOk());
        Order paidEgp = orderRepository.findById(egpOrder).orElseThrow();
        assertThat(paidEgp.getSettledAmountEgp()).isEqualByComparingTo(paidEgp.getAmount());

        // USD order from Kuwait through the mock gateway: settled = amount × fx[USD] / fx[EGP]
        User kuwaiti = createUser("egp-kw", Role.USER);
        kuwaiti.setCountryCode("KW");
        userRepository.save(kuwaiti);
        UUID usdOrder = checkout(kuwaiti, null).andExpect(status().isOk()).andReturnOrderId();
        mvc.perform(post(ApiPaths.Webhooks.ROOT + "/mock/" + usdOrder).param("success", "true"))
                .andExpect(status().isOk());
        Order paidUsd = orderRepository.findById(usdOrder).orElseThrow();
        assertThat(paidUsd.getStatus()).isEqualTo(OrderStatus.SUCCESS);
        assertThat(paidUsd.getCurrency().name()).isEqualTo("USD");
        // seeded rates: EGP 0.0208, USD 1 → 1 USD ≈ 48.08 EGP
        BigDecimal expected = paidUsd.getAmount().multiply(BigDecimal.ONE)
                .divide(new BigDecimal("0.0208"), 2, java.math.RoundingMode.HALF_UP);
        assertThat(paidUsd.getSettledAmountEgp()).isEqualByComparingTo(expected);
        assertThat(paidUsd.getSettledAmountEgp()).isGreaterThan(paidUsd.getAmount());

        JsonNode dash = json(mvc.perform(get(ApiPaths.Admin.ANALYTICS_COUNTRIES).param("period", "all")
                        .header("Authorization", bearer(interpreter)).header("Accept-Language", "en"))
                .andExpect(status().isOk()));
        assertThat(dash.get("baseCurrency").asText()).isEqualTo("EGP");

        // every list holds only non-zero rows, highest first
        assertNonZeroDescending(dash.get("topVisits"), "visits");
        assertNonZeroDescending(dash.get("topDreams"), "dreams");
        assertNonZeroDescending(dash.get("topRevenue"), "revenueBase");

        JsonNode kw = null;
        for (JsonNode row : dash.get("topRevenue")) {
            if ("KW".equals(row.get("countryCode").asText())) {
                kw = row;
            }
        }
        assertThat(kw).as("Kuwait is ranked by its EGP revenue").isNotNull();
        assertThat(kw.get("revenueBase").decimalValue()).isGreaterThanOrEqualTo(paidUsd.getSettledAmountEgp());
        List<String> currencies = new ArrayList<>();
        kw.get("revenueByCurrency").forEach(r -> currencies.add(r.get("currency").asText()));
        assertThat(currencies).contains("USD");
        // a country with no dream submitted is not in topDreams
        for (JsonNode row : dash.get("topDreams")) {
            assertThat(row.get("dreams").asLong()).isPositive();
        }

        JsonNode summary = json(mvc.perform(get(ApiPaths.Admin.ANALYTICS_SUMMARY)
                .header("Authorization", bearer(interpreter))).andExpect(status().isOk()));
        assertThat(summary.get("revenueEgp").decimalValue())
                .isGreaterThanOrEqualTo(paidEgp.getSettledAmountEgp().add(paidUsd.getSettledAmountEgp()));
    }

    // ------------------------------------------------------------------ helpers

    private static void assertNonZeroDescending(JsonNode rows, String field) {
        double previous = Double.MAX_VALUE;
        for (JsonNode row : rows) {
            double value = row.get(field).asDouble();
            assertThat(value).as("%s of %s", field, row.get("countryCode").asText()).isPositive();
            assertThat(value).as("%s order", field).isLessThanOrEqualTo(previous);
            previous = value;
        }
    }

    private List<Notification> notifications(User user) {
        return notificationRepository.findByUserIdOrderByCreatedAtDesc(user.getId(), Pageable.unpaged()).getContent();
    }

    private List<UUID> intentOf(UUID orderId) {
        String json = orderRepository.findById(orderId).orElseThrow().getCheckoutIntent();
        List<UUID> ids = new ArrayList<>();
        if (json == null) {
            return ids;
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            if (node.isTextual()) {
                node = objectMapper.readTree(node.asText());
            }
            node.path("dreamIds").forEach(x -> ids.add(UUID.fromString(x.asText())));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        return ids;
    }

    private long editedNotices(User interpreter, UUID dreamId) {
        return notifications(interpreter).stream()
                .filter(x -> x.getType() == NotificationType.DREAM_EDITED && ("/admin/dreams/" + dreamId).equals(x.getLink()))
                .count();
    }

    private ResultActions release(User user, UUID orderId, List<UUID> dreamIds) throws Exception {
        var request = post(ApiPaths.Me.ORDERS + "/" + orderId + "/release-dreams").header("Authorization", bearer(user));
        if (dreamIds != null) {
            request = request.contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(Map.of("dreamIds",
                            dreamIds.stream().map(UUID::toString).toList())));
        }
        return mvc.perform(request);
    }

    private ResultActions ask(User interpreter, UUID id) throws Exception {
        return mvc.perform(post(ApiPaths.Admin.DREAMS + "/" + id + "/messages").header("Authorization", bearer(interpreter))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"هل البيت القديم هو بيت أهلك؟\"}"));
    }

    private ResultActions reply(User user, UUID id) throws Exception {
        return mvc.perform(post(ApiPaths.Dreams.ROOT + "/" + id + "/messages").header("Authorization", bearer(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"نعم، بيت جدي.\"}"));
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

    private ResultActions edit(User user, UUID id, String text) throws Exception {
        return mvc.perform(put(ApiPaths.Dreams.ROOT + "/" + id).header("Authorization", bearer(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("text", text, "gender", "MALE"))));
    }

    private List<UUID> listIds(User user, String status) throws Exception {
        JsonNode page = json(mvc.perform(get(ApiPaths.Dreams.ROOT).param("status", status).param("size", "100")
                .header("Authorization", bearer(user))).andExpect(status().isOk()));
        List<UUID> ids = new ArrayList<>();
        page.get("items").forEach(i -> ids.add(UUID.fromString(i.get("id").asText())));
        return ids;
    }

    private List<String> orderIds(User interpreter, String scope) throws Exception {
        JsonNode page = json(mvc.perform(get(ApiPaths.Admin.ORDERS).param("scope", scope).param("size", "100")
                .header("Authorization", bearer(interpreter))).andExpect(status().isOk()));
        List<String> ids = new ArrayList<>();
        page.get("items").forEach(i -> ids.add(i.get("id").asText()));
        return ids;
    }

    private CheckoutResult checkout(User user, UUID draft) throws Exception {
        return draft == null ? checkout(user, PACKAGE_ONE) : checkout(user, PACKAGE_ONE, draft);
    }

    private CheckoutResult checkout(User user, String packageId, UUID... drafts) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("packageId", packageId);
        if (drafts.length > 0) {
            body.put("dreamIds", java.util.Arrays.stream(drafts).map(UUID::toString).toList());
        }
        return new CheckoutResult(mvc.perform(post(ApiPaths.Checkout.ROOT).header("Authorization", bearer(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body))));
    }

    /** Wraps the checkout result so a test can assert the status, then read the order id. */
    private final class CheckoutResult {
        private final ResultActions actions;

        CheckoutResult(ResultActions actions) {
            this.actions = actions;
        }

        CheckoutResult andExpect(org.springframework.test.web.servlet.ResultMatcher matcher) throws Exception {
            actions.andExpect(matcher);
            return this;
        }

        UUID andReturnOrderId() throws Exception {
            return UUID.fromString(json(actions).get("orderId").asText());
        }
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
