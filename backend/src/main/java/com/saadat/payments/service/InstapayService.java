package com.saadat.payments.service;

import com.saadat.common.audit.AuditService;
import com.saadat.common.domain.DreamStatus;
import com.saadat.common.domain.NotificationType;
import com.saadat.common.domain.OrderStatus;
import com.saadat.common.domain.PaymentProviderType;
import com.saadat.common.error.ConflictException;
import com.saadat.common.error.NotFoundException;
import com.saadat.common.error.ValidationException;
import com.saadat.dreams.domain.Dream;
import com.saadat.dreams.repo.DreamRepository;
import com.saadat.mail.EventMailer;
import com.saadat.mail.MailService;
import com.saadat.mail.MailTemplates;
import com.saadat.notifications.service.NotificationService;
import com.saadat.payments.api.InstapayDtos;
import com.saadat.payments.domain.Order;
import com.saadat.payments.provider.InstapayProvider;
import com.saadat.payments.provider.WebhookEvent;
import com.saadat.payments.repo.OrderRepository;
import com.saadat.settings.SettingKeys;
import com.saadat.settings.SettingsService;
import com.saadat.users.domain.User;
import com.saadat.users.repo.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manual InstaPay transfers (Egypt, EGP). The buyer transfers the exact amount in the InstaPay app and sends the
 * transfer reference number (and, optionally, a dream). The interpreter checks her InstaPay history and accepts
 * (credits added through {@link PaymentService#confirm}, the attached dreams are submitted) or rejects (the buyer
 * is told and may correct the reference). No credit is ever added before the interpreter accepts.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InstapayService {

    /** Statuses in which a reference is "taken" (mirrors the V34 partial unique index). */
    static final Set<OrderStatus> LIVE = Set.of(OrderStatus.PENDING_REVIEW, OrderStatus.SUCCESS,
            OrderStatus.SUSPICIOUS, OrderStatus.REFUNDED);
    static final List<OrderStatus> REVIEWED = List.of(OrderStatus.SUCCESS, OrderStatus.FAILED,
            OrderStatus.SUSPICIOUS);
    public static final String REASON_REJECTED = "TRANSFER_REJECTED";
    public static final String LINK_ADMIN = "/admin/orders";

    private static final Pattern REFERENCE = Pattern.compile("\\d{6,24}");
    private static final Pattern ADDRESS = Pattern.compile("[a-z0-9][a-z0-9._-]{1,62}@instapay");
    private static final Pattern LINK = Pattern.compile("https://ipn\\.eg/S/[A-Za-z0-9._-]+/instapay/[A-Za-z0-9]+");
    private static final int SENDER_MIN = 3;

    private final OrderRepository orderRepository;
    private final DreamRepository dreamRepository;
    private final UserRepository userRepository;
    private final PaymentService paymentService;
    private final DreamAttachments dreamAttachments;
    private final NotificationService notificationService;
    private final EventMailer eventMailer;
    private final AuditService auditService;
    private final SettingsService settings;
    private final Clock clock;

    // ================================================================== buyer

    @Transactional(readOnly = true)
    public InstapayDtos.OrderView view(UUID userId, UUID orderId) {
        return toView(owned(userId, orderId, false));
    }

    /**
     * Records the transfer details: INITIATED (not expired) → PENDING_REVIEW. While PENDING_REVIEW, or after a
     * rejection, the buyer may correct them (a rejection is reopened for review).
     */
    @Transactional
    public InstapayDtos.OrderView submit(UUID userId, UUID orderId, InstapayDtos.SubmitRequest req) {
        Order order = owned(userId, orderId, true);
        Instant now = clock.instant();
        boolean firstTime = order.getStatus() == OrderStatus.INITIATED;
        boolean reopened = order.getStatus() == OrderStatus.FAILED && REASON_REJECTED.equals(order.getFailureReason());
        // the expiry job marks it EXPIRED; refusing here is enough (a write would roll back with the error)
        if (firstTime && order.getExpiresAt() != null && !order.getExpiresAt().isAfter(now)) {
            throw new ConflictException("This order expired, please choose the package again", "ORDER_EXPIRED");
        }
        if (!firstTime && !reopened && order.getStatus() != OrderStatus.PENDING_REVIEW) {
            throw new ConflictException("This order can no longer be changed", "ORDER_CLOSED");
        }

        String reference = normalizeReference(req.reference());
        String sender = cleanSender(req.senderName());
        if (orderRepository.existsByTransferReferenceAndStatusInAndIdNot(reference, LIVE, order.getId())) {
            throw new ConflictException("This transfer reference was already used", "DUPLICATE_REFERENCE");
        }
        List<UUID> intent = mergeDreams(userId, order, req.dreamIds());

        String before = order.getStatus().name();
        order.setTransferReference(reference);
        order.setTransferSenderName(sender);
        order.setCheckoutIntent(paymentService.intentJson(intent));
        order.setStatus(OrderStatus.PENDING_REVIEW);
        if (firstTime || reopened || order.getTransferSubmittedAt() == null) {
            order.setTransferSubmittedAt(now);
        }
        if (reopened) {
            order.setFailureReason(null);
            order.setReviewNote(null);
            order.setReviewedBy(null);
            order.setReviewedAt(null);
        }
        try {
            orderRepository.saveAndFlush(order);
        } catch (DataIntegrityViolationException e) {
            // a concurrent submit took the reference between the check and the write
            throw new ConflictException("This transfer reference was already used", "DUPLICATE_REFERENCE");
        }
        auditService.record(userId, "INSTAPAY_SUBMITTED", "orders", order.getId().toString(),
                Map.of("status", before), Map.of("status", order.getStatus().name(), "reference", reference,
                        "senderName", sender, "dreams", intent.size()));

        if (firstTime || reopened) {
            User buyer = userRepository.findById(userId).orElseThrow();
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("userName", buyer.getName());
            args.put("amount", order.getAmount().stripTrailingZeros().toPlainString());
            args.put("currency", order.getCurrency().name());
            args.put("reference", reference);
            args.put("packageName", order.getPackageNameSnapshot());
            notificationService.notifyInterpreters(NotificationType.TRANSFER_SUBMITTED, args, LINK_ADMIN);
        }
        return toView(order);
    }

    // ================================================================== interpreter

    @Transactional(readOnly = true)
    public InstapayDtos.TransferList transfers() {
        List<Order> pending = orderRepository.findByProviderAndStatusOrderByTransferSubmittedAtAsc(
                PaymentProviderType.INSTAPAY, OrderStatus.PENDING_REVIEW);
        List<Order> reviewed = orderRepository.findTop50ByProviderAndStatusInAndReviewedAtIsNotNullOrderByReviewedAtDesc(
                PaymentProviderType.INSTAPAY, REVIEWED);
        Set<UUID> userIds = new LinkedHashSet<>();
        pending.forEach(o -> userIds.add(o.getUserId()));
        reviewed.forEach(o -> userIds.add(o.getUserId()));
        Map<UUID, User> users = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        return new InstapayDtos.TransferList(
                pending.stream().map(o -> toRow(o, users.get(o.getUserId()))).toList(),
                reviewed.stream().map(o -> toRow(o, users.get(o.getUserId()))).toList());
    }

    @Transactional(readOnly = true)
    public InstapayDtos.Counts counts() {
        return new InstapayDtos.Counts(orderRepository.countByProviderAndStatus(PaymentProviderType.INSTAPAY,
                OrderStatus.PENDING_REVIEW));
    }

    /**
     * The money arrived: the same confirmation path as a gateway webhook (credits, coupon, dreams, receipt). The
     * interpreter sends back the reference she checked; a reference changed by the buyer since then is refused.
     */
    @Transactional
    public InstapayDtos.TransferRow approve(UUID interpreterId, UUID orderId, String checkedReference) {
        Order order = pendingForUpdate(orderId);
        // the buyer may edit a pending transfer: approve only the reference the interpreter actually checked
        String checked = checkedReference == null ? "" : checkedReference.replaceAll("\\s", "");
        if (!checked.equals(order.getTransferReference())) {
            throw new ConflictException("The transfer reference was changed, check it again", "REFERENCE_CHANGED");
        }
        order.setReviewedBy(interpreterId);
        order.setReviewedAt(clock.instant());
        // flushed first: confirm() reloads the row under lock
        orderRepository.saveAndFlush(order);

        WebhookEvent event = new WebhookEvent(InstapayProvider.TXN_PREFIX + order.getTransferReference(),
                order.getProviderOrderId(), order.getId(), true, order.getAmount(), order.getCurrency().name(),
                null, "instapay-approved-by:" + interpreterId);
        PaymentService.ConfirmOutcome outcome = paymentService.confirm(event);
        if (outcome != PaymentService.ConfirmOutcome.SUCCESS) {
            log.warn("InstaPay approval of order {} ended as {}", orderId, outcome);
            throw new ConflictException("The transfer could not be confirmed (" + outcome + ")",
                    "CONFIRM_" + outcome.name());
        }
        auditService.record(interpreterId, "INSTAPAY_APPROVED", "orders", orderId.toString(),
                Map.of("status", OrderStatus.PENDING_REVIEW.name()), Map.of("status", OrderStatus.SUCCESS.name()));
        Order saved = orderRepository.findById(orderId).orElseThrow();
        return toRow(saved, userRepository.findById(saved.getUserId()).orElse(null));
    }

    /** No matching transfer: FAILED, no credits; the buyer is told (in-app + push + e-mail) and may correct it. */
    @Transactional
    public InstapayDtos.TransferRow reject(UUID interpreterId, UUID orderId, String note) {
        Order order = pendingForUpdate(orderId);
        String cleanNote = note == null || note.isBlank() ? null : note.trim();
        order.setStatus(OrderStatus.FAILED);
        order.setFailureReason(REASON_REJECTED);
        order.setReviewNote(cleanNote);
        order.setReviewedBy(interpreterId);
        order.setReviewedAt(clock.instant());
        orderRepository.save(order);
        auditService.record(interpreterId, "INSTAPAY_REJECTED", "orders", orderId.toString(),
                Map.of("status", OrderStatus.PENDING_REVIEW.name()),
                Map.of("status", OrderStatus.FAILED.name(), "note", cleanNote == null ? "" : cleanNote));

        User buyer = userRepository.findById(order.getUserId()).orElse(null);
        if (buyer != null && !buyer.isDeleted()) {
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("packageName", order.getPackageNameSnapshot());
            args.put("amount", order.getAmount().stripTrailingZeros().toPlainString());
            args.put("currency", order.getCurrency().name());
            notificationService.notify(buyer.getId(), NotificationType.PAYMENT_REJECTED, args,
                    InstapayProvider.CHECKOUT_PATH + orderId);

            Map<String, Object> model = new LinkedHashMap<>();
            model.put("orderRef", PaymentService.orderRef(order));
            model.put("packageName", order.getPackageNameSnapshot());
            model.put("amount", order.getAmount());
            model.put("currency", order.getCurrency().name());
            model.put(MailService.MODEL_LINK, InstapayProvider.CHECKOUT_PATH + orderId);
            eventMailer.toUserOnce(buyer, MailTemplates.PAYMENT_FAILED, model,
                    MailTemplates.PAYMENT_FAILED + ":" + order.getId() + ":" + order.getTransferReference());
        }
        return toRow(order, buyer);
    }

    // ================================================================== settings

    @Transactional(readOnly = true)
    public InstapayDtos.Settings settings() {
        return new InstapayDtos.Settings(
                settings.getBool(SettingKeys.INSTAPAY_ENABLED, false),
                settings.getString(SettingKeys.INSTAPAY_ADDRESS, ""),
                settings.getString(SettingKeys.INSTAPAY_LINK, ""),
                settings.getString(SettingKeys.INSTAPAY_RECIPIENT_NAME, ""),
                settings.getInt(SettingKeys.INSTAPAY_EXPIRE_HOURS, 24));
    }

    /** Validated: the address must be name@instapay and the link an official https://ipn.eg/S/… payment link. */
    @Transactional
    public InstapayDtos.Settings saveSettings(UUID interpreterId, InstapayDtos.Settings body) {
        String address = body.address().trim().toLowerCase(Locale.ROOT);
        if (!ADDRESS.matcher(address).matches()) {
            throw new ValidationException("The InstaPay address must look like name@instapay", "INVALID_ADDRESS");
        }
        String link = body.link().trim();
        if (!LINK.matcher(link).matches()) {
            throw new ValidationException("The payment link must start with https://ipn.eg/S/", "INVALID_LINK");
        }
        String recipient = body.recipientName() == null ? "" : body.recipientName().trim();
        Map<String, String> values = new LinkedHashMap<>();
        values.put(SettingKeys.INSTAPAY_ENABLED, String.valueOf(body.enabled()));
        values.put(SettingKeys.INSTAPAY_ADDRESS, address);
        values.put(SettingKeys.INSTAPAY_LINK, link);
        values.put(SettingKeys.INSTAPAY_RECIPIENT_NAME, recipient);
        values.put(SettingKeys.INSTAPAY_EXPIRE_HOURS, String.valueOf(body.expireHours()));
        settings.putAll(values, interpreterId);
        return new InstapayDtos.Settings(body.enabled(), address, link, recipient, body.expireHours());
    }

    // ================================================================== helpers

    /** Digits only: spaces and dashes removed, Arabic-Indic and Persian digits turned into 0-9. */
    static String normalizeReference(String raw) {
        StringBuilder sb = new StringBuilder();
        for (char c : (raw == null ? "" : raw).toCharArray()) {
            if (c >= '٠' && c <= '٩') {
                sb.append((char) ('0' + (c - '٠')));
            } else if (c >= '۰' && c <= '۹') {
                sb.append((char) ('0' + (c - '۰')));
            } else if (!Character.isWhitespace(c) && c != '-' && c != '‏' && c != '‎') {
                sb.append(c);
            }
        }
        String ref = sb.toString();
        if (!REFERENCE.matcher(ref).matches()) {
            throw new ValidationException("The transfer reference is a number of 6 to 24 digits", "INVALID_REFERENCE");
        }
        return ref;
    }

    static String cleanSender(String raw) {
        String name = raw == null ? "" : raw.trim().replaceAll("\\s+", " ");
        if (name.length() < SENDER_MIN) {
            throw new ValidationException("Write the sender's name as InstaPay shows it", "INVALID_SENDER");
        }
        return name;
    }

    /**
     * The order's intended drafts (still the caller's drafts) plus the new ones (each must be one of the caller's
     * DRAFT dreams and not held by another payment in progress or under review). A dream taken from a rejected order
     * leaves that order. 400 TOO_MANY_DREAMS when the order would carry more dreams than the credits it buys.
     */
    private List<UUID> mergeDreams(UUID userId, Order order, List<UUID> extra) {
        Set<UUID> wanted = extra == null ? new LinkedHashSet<>() : new LinkedHashSet<>(extra);
        wanted.remove(null);
        Set<UUID> all = new LinkedHashSet<>(paymentService.readIntent(order.getCheckoutIntent()));
        all.addAll(wanted);
        // read without a row lock (lock order elsewhere is dreams → orders; here the order is already locked)
        List<Dream> rows = all.isEmpty() ? List.<Dream>of() : dreamRepository.findAllById(all).stream()
                .filter(d -> userId.equals(d.getUserId()))
                .toList();
        Set<UUID> drafts = rows.stream().filter(d -> d.getStatus() == DreamStatus.DRAFT).map(Dream::getId)
                .collect(Collectors.toSet());
        if (!drafts.containsAll(wanted)) {
            throw new ValidationException("Only your own draft dreams can be attached", "INVALID_DREAMS");
        }
        // this order's own dreams are fine; a dream held by another live order of the user is not (contract §2)
        dreamAttachments.requireFree(userId, wanted, order.getId());
        List<UUID> ids = new ArrayList<>(all.stream().filter(drafts::contains).toList());
        DreamAttachments.requireWithinCredits(ids.size(), order.getCredits());
        dreamAttachments.detachFromRejected(userId, wanted, order.getId());
        return ids;
    }

    private Order owned(UUID userId, UUID orderId, boolean lock) {
        return (lock ? orderRepository.findByIdForUpdate(orderId) : orderRepository.findById(orderId))
                .filter(o -> o.getUserId().equals(userId) && o.getProvider() == PaymentProviderType.INSTAPAY)
                .orElseThrow(() -> NotFoundException.of("Order", orderId));
    }

    private Order pendingForUpdate(UUID orderId) {
        Order order = orderRepository.findByIdForUpdate(orderId)
                .filter(o -> o.getProvider() == PaymentProviderType.INSTAPAY)
                .orElseThrow(() -> NotFoundException.of("Order", orderId));
        if (order.getStatus() != OrderStatus.PENDING_REVIEW) {
            throw new ConflictException("This transfer was already reviewed", "NOT_PENDING");
        }
        return order;
    }

    private InstapayDtos.OrderView toView(Order o) {
        return new InstapayDtos.OrderView(o.getId(), o.getOrderNumber(), o.getStatus(), o.getPackageNameSnapshot(),
                o.getCredits(),
                o.getAmount(), o.getCurrency(),
                settings.getString(SettingKeys.INSTAPAY_ADDRESS, ""),
                settings.getString(SettingKeys.INSTAPAY_LINK, ""),
                settings.getString(SettingKeys.INSTAPAY_RECIPIENT_NAME, ""),
                o.getCreatedAt(), o.getExpiresAt(), o.getTransferReference(), o.getTransferSenderName(),
                o.getTransferSubmittedAt(), o.getReviewNote(), paymentService.readIntent(o.getCheckoutIntent()).size());
    }

    private InstapayDtos.TransferRow toRow(Order o, User u) {
        return new InstapayDtos.TransferRow(o.getId(), o.getOrderNumber(), o.getUserId(), u == null ? "" : u.getName(),
                u == null ? "" : u.getEmail(), o.getPackageNameSnapshot(), o.getCredits(), o.getAmount(),
                o.getCurrency(), o.getStatus(), o.getTransferReference(), o.getTransferSenderName(), o.getCreatedAt(),
                o.getTransferSubmittedAt(), o.getReviewedAt(), o.getReviewNote(),
                paymentService.readIntent(o.getCheckoutIntent()).size());
    }

    /** SPA link of the buyer's payment page (used in e-mails and notifications). */
    public static String pageLink(UUID orderId) {
        return InstapayProvider.CHECKOUT_PATH + orderId;
    }

}
