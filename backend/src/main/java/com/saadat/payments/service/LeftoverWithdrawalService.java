package com.saadat.payments.service;

import com.saadat.common.audit.AuditService;
import com.saadat.common.domain.NotificationType;
import com.saadat.common.domain.OrderStatus;
import com.saadat.common.error.ConflictException;
import com.saadat.common.error.NotFoundException;
import com.saadat.common.util.CreditCounts;
import com.saadat.credits.service.CreditService;
import com.saadat.mail.EventMailer;
import com.saadat.mail.FrontendPaths;
import com.saadat.mail.MailService;
import com.saadat.mail.MailTemplates;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Withdraws the unused credits of a paid order (docs/PAYMENTS_FILTERS_CONTRACT.md §2): typically when a dream of a
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

    private final OrderRepository orderRepository;
    private final UserRepository userRepository;
    private final CreditService creditService;
    private final OrderQueryService orderQueryService;
    private final NotificationService notificationService;
    private final EventMailer eventMailer;
    private final AuditService auditService;
    private final Clock clock;

    /**
     * One transaction, lock order: order row, then the user's credit balance (as approve / revoke). 404 unknown
     * order, 409 NOT_WITHDRAWABLE unless SUCCESS, 409 NOTHING_TO_WITHDRAW when no unused, unexpired credit is left.
     */
    @Transactional
    public AdminOrderDtos.Detail withdraw(UUID interpreterId, UUID orderId, String note) {
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> NotFoundException.of("Order", orderId));
        if (order.getStatus() != OrderStatus.SUCCESS) {
            throw new ConflictException("Only the credits of a paid order can be withdrawn", CODE_NOT_WITHDRAWABLE);
        }
        String cleanNote = note == null || note.isBlank() ? null : note.trim();
        UUID userId = order.getUserId();
        int withdrawn = creditService.revokeOrderCredits(userId, orderId, cleanNote, interpreterId);
        if (withdrawn <= 0) {
            throw new ConflictException("This payment has no unused credit left", CODE_NOTHING_TO_WITHDRAW);
        }
        Instant now = clock.instant();
        int before = order.getLeftoverWithdrawnCredits() == null ? 0 : order.getLeftoverWithdrawnCredits();
        order.setLeftoverWithdrawnAt(now);
        order.setLeftoverWithdrawnCredits(before + withdrawn);
        order.setLeftoverNote(cleanNote);
        orderRepository.save(order);

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("credits", withdrawn);
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
            notificationService.notify(userId, NotificationType.CREDITS_WITHDRAWN, args, FrontendPaths.MY_PAYMENTS);

            Map<String, Object> model = new LinkedHashMap<>();
            model.put("orderRef", orderRef);
            model.put("packageName", order.getPackageNameSnapshot());
            model.put("credits", withdrawn);
            model.put("creditsText", CreditCounts.text(withdrawn, buyer.getLocale()));
            model.put("note", cleanNote);
            model.put("balance", creditService.lockAndGetBalance(userId));
            model.put(MailService.MODEL_LINK, FrontendPaths.MY_PAYMENTS);
            eventMailer.toUserOnce(buyer, MailTemplates.CREDITS_WITHDRAWN, model,
                    MailTemplates.CREDITS_WITHDRAWN + ":" + orderId + ":" + now.toEpochMilli());
        }
        return orderQueryService.detail(orderId);
    }
}
