package com.saadat.payments.service;

import com.saadat.common.audit.AuditService;
import com.saadat.common.domain.LedgerKind;
import com.saadat.common.domain.NotificationType;
import com.saadat.common.domain.OrderStatus;
import com.saadat.common.domain.PaymentProviderType;
import com.saadat.common.error.ApiException;
import com.saadat.common.error.ConflictException;
import com.saadat.common.error.NotFoundException;
import com.saadat.common.util.CreditCounts;
import com.saadat.credits.service.CreditService;
import com.saadat.mail.EventMailer;
import com.saadat.mail.FrontendPaths;
import com.saadat.mail.MailService;
import com.saadat.mail.MailTemplates;
import com.saadat.mail.MessageText;
import com.saadat.notifications.service.NotificationService;
import com.saadat.payments.api.AdminOrderDtos;
import com.saadat.payments.domain.Order;
import com.saadat.payments.repo.OrderRepository;
import com.saadat.users.domain.User;
import com.saadat.users.repo.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Withdraws the unused credits of an approved InstaPay order (docs/PAYMENTS_FILTERS_CONTRACT.md §2), or gives them
 * back: typically when a dream of a
 * doubtful payment was already interpreted, so the approval cannot be taken back. The order stays SUCCESS and its dreams
 * are not touched; one REVOKE ledger row per lot of the order removes what is left (expired credits are not counted
 * twice, the balance never goes below zero). The buyer is told (in-app + push CREDITS_WITHDRAWN, credits-withdrawn
 * e-mail).
 */
@Service
@RequiredArgsConstructor
public class LeftoverWithdrawalService {

    public static final String CODE_NOT_WITHDRAWABLE = "NOT_WITHDRAWABLE";
    public static final String CODE_NOTHING_TO_WITHDRAW = "NOTHING_TO_WITHDRAW";
    public static final String CODE_NOTHING_TO_RESTORE = "NOTHING_TO_RESTORE";
    public static final String CODE_NOTE_REQUIRED = "NOTE_REQUIRED";

    private final OrderRepository orderRepository;
    private final UserRepository userRepository;
    private final CreditService creditService;
    private final OrderQueryService orderQueryService;
    private final NotificationService notificationService;
    private final EventMailer eventMailer;
    private final AuditService auditService;
    private final MessageText messageText;
    private final Clock clock;

    /** Why the unused credits are withdrawn (shown to the buyer). */
    public enum Reason {
        /** The interpreter could not confirm the payment. */
        UNCONFIRMED,
        /** Less money arrived than the package price. */
        AMOUNT_SHORT,
        /** Another reason, written in the note (required). */
        OTHER
    }

    /**
     * One transaction, lock order: order row, then the user's credit balance (as approve / revoke). 404 unknown
     * order; 409 NOT_WITHDRAWABLE unless an InstaPay order in SUCCESS; 400 NOTE_REQUIRED for OTHER without a note;
     * 409 NOTHING_TO_WITHDRAW when no unused, unexpired credit is left.
     */
    @Transactional
    public AdminOrderDtos.Detail withdraw(UUID interpreterId, UUID orderId, Reason reason, String note) {
        Reason why = reason == null ? Reason.UNCONFIRMED : reason;
        String cleanNote = note == null || note.isBlank() ? null : note.trim();
        if (why == Reason.OTHER && cleanNote == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "bad-request", CODE_NOTE_REQUIRED,
                    "Write the reason in the note").with("field", "note");
        }
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> NotFoundException.of("Order", orderId));
        if (order.getProvider() != PaymentProviderType.INSTAPAY || order.getStatus() != OrderStatus.SUCCESS) {
            throw new ConflictException("Only the credits of an approved InstaPay payment can be withdrawn",
                    CODE_NOT_WITHDRAWABLE);
        }
        UUID userId = order.getUserId();
        int withdrawn = creditService.revokeOrderCredits(userId, orderId, cleanNote, interpreterId,
                LedgerKind.LEFTOVER_WITHDRAWN);
        if (withdrawn <= 0) {
            throw new ConflictException("This payment has no unused credit left", CODE_NOTHING_TO_WITHDRAW);
        }
        Instant now = clock.instant();
        int before = order.getLeftoverWithdrawnCredits() == null ? 0 : order.getLeftoverWithdrawnCredits();
        order.setLeftoverWithdrawnAt(now);
        order.setLeftoverWithdrawnCredits(before + withdrawn);
        order.setLeftoverNote(cleanNote);
        order.setLeftoverReason(why.name());
        orderRepository.save(order);

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("credits", withdrawn);
        after.put("reason", why.name());
        after.put("note", cleanNote == null ? "" : cleanNote);
        auditService.record(interpreterId, "ORDER_LEFTOVER_WITHDRAWN", "orders", orderId.toString(),
                Map.of("status", order.getStatus().name()), after);

        User buyer = userRepository.findById(userId).orElse(null);
        if (buyer != null && !buyer.isDeleted()) {
            String orderRef = PaymentService.orderRef(order);
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("orderNumber", orderRef);
            args.put("credits", withdrawn);
            args.put("creditsText", CreditCounts.text(withdrawn, buyer.getLocale()));
            // the note ends the sentence of an OTHER reason: no doubled full stop
            args.put("note", cleanNote == null ? "" : cleanNote.replaceAll("[.。۔]+$", ""));
            // the first sentence depends on the reason (the note is the reason for OTHER)
            String lead = messageText.get("notif.CREDITS_WITHDRAWN.lead." + why.name(), buyer.getLocale(), args);
            args.put("lead", lead);
            notificationService.notify(userId, NotificationType.CREDITS_WITHDRAWN, args, FrontendPaths.MY_PAYMENTS);

            Map<String, Object> model = new LinkedHashMap<>();
            model.put("orderRef", orderRef);
            model.put("packageName", order.getPackageNameSnapshot());
            model.put("credits", withdrawn);
            model.put("creditsText", CreditCounts.text(withdrawn, buyer.getLocale()));
            model.put("lead", lead);
            model.put("reason", why.name());
            model.put("note", why == Reason.OTHER ? null : cleanNote);
            model.put("balance", creditService.lockAndGetBalance(userId));
            model.put(MailService.MODEL_LINK, FrontendPaths.MY_PAYMENTS);
            eventMailer.toUserOnce(buyer, MailTemplates.CREDITS_WITHDRAWN, model,
                    MailTemplates.CREDITS_WITHDRAWN + ":" + orderId + ":" + now.toEpochMilli());
        }
        return orderQueryService.detail(orderId);
    }

    /**
     * Gives the withdrawn credits back (a withdrawal by mistake): to the order's lot with its ORIGINAL expiry
     * (credits whose lot already expired are not given back), clears the withdrawal fields, audits
     * ORDER_LEFTOVER_RESTORED and tells the buyer (in-app + push CREDITS_RESTORED, no e-mail). 409 NOTHING_TO_RESTORE
     * unless SUCCESS with withdrawn credits that can still be given back.
     */
    @Transactional
    public AdminOrderDtos.Detail restore(UUID interpreterId, UUID orderId) {
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> NotFoundException.of("Order", orderId));
        if (order.getStatus() != OrderStatus.SUCCESS || order.getLeftoverWithdrawnCredits() == null
                || order.getLeftoverWithdrawnCredits() <= 0) {
            throw new ConflictException("This payment has no withdrawn credit to give back", CODE_NOTHING_TO_RESTORE);
        }
        UUID userId = order.getUserId();
        int restored = creditService.restoreLeftover(userId, orderId, null, interpreterId);
        if (restored <= 0) {
            throw new ConflictException("The withdrawn credits have expired", CODE_NOTHING_TO_RESTORE);
        }
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("leftoverWithdrawnCredits", order.getLeftoverWithdrawnCredits());
        before.put("reason", order.getLeftoverReason() == null ? "" : order.getLeftoverReason());
        order.setLeftoverWithdrawnAt(null);
        order.setLeftoverWithdrawnCredits(null);
        order.setLeftoverNote(null);
        order.setLeftoverReason(null);
        orderRepository.save(order);
        auditService.record(interpreterId, "ORDER_LEFTOVER_RESTORED", "orders", orderId.toString(), before,
                Map.of("credits", restored));

        User buyer = userRepository.findById(userId).orElse(null);
        if (buyer != null && !buyer.isDeleted()) {
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("orderNumber", PaymentService.orderRef(order));
            args.put("credits", restored);
            args.put("creditsText", CreditCounts.text(restored, buyer.getLocale()));
            notificationService.notify(userId, NotificationType.CREDITS_RESTORED, args, FrontendPaths.MY_PAYMENTS);
        }
        return orderQueryService.detail(orderId);
    }
}
