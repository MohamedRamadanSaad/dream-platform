package com.saadat.payments.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.saadat.common.audit.AuditService;
import com.saadat.common.domain.CountrySource;
import com.saadat.common.domain.LedgerReason;
import com.saadat.common.domain.Locale;
import com.saadat.common.domain.NotificationType;
import com.saadat.common.domain.OrderStatus;
import com.saadat.common.domain.ProductType;
import com.saadat.common.error.NotFoundException;
import com.saadat.common.error.ValidationException;
import com.saadat.common.tx.AfterCommit;
import com.saadat.common.web.CountryResolver.ResolvedCountry;
import com.saadat.credits.service.CreditService;
import com.saadat.dreams.service.DreamService;
import com.saadat.mail.FrontendPaths;
import com.saadat.mail.MailService;
import com.saadat.mail.MailTemplates;
import com.saadat.notifications.service.InterpreterDirectory;
import com.saadat.notifications.service.NotificationService;
import com.saadat.payments.api.CheckoutRequest;
import com.saadat.payments.api.CheckoutResponse;
import com.saadat.payments.api.OrderDto;
import com.saadat.payments.domain.Order;
import com.saadat.payments.provider.CheckoutSession;
import com.saadat.payments.provider.PaymentProvider;
import com.saadat.payments.provider.ProviderRegistry;
import com.saadat.payments.provider.WebhookEvent;
import com.saadat.payments.repo.OrderRepository;
import com.saadat.pricing.domain.Country;
import com.saadat.pricing.domain.Coupon;
import com.saadat.pricing.domain.DreamPackage;
import com.saadat.pricing.repo.DreamPackageRepository;
import com.saadat.pricing.service.CouponService;
import com.saadat.pricing.service.PriceResolver;
import com.saadat.pricing.service.PromotionService;
import com.saadat.settings.SettingKeys;
import com.saadat.settings.SettingsService;
import com.saadat.users.domain.User;
import com.saadat.users.repo.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Checkout (server-side pricing, contract rule 1) and payment confirmation (rules 3 + 4).
 *
 * <p>{@link #confirm} is the ONLY way an order becomes SUCCESS: idempotent on {@code provider_txn_id},
 * amount + currency must equal the stored order, card-issuer country must equal the order country (when the
 * provider exposes it), then credits are added (ledger PURCHASE) and the drafts in {@code checkout_intent}
 * are auto-submitted. Notifications/e-mails run after commit.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    public static final String MAIL_RECEIPT = MailTemplates.PAYMENT_RECEIPT;
    public static final String MAIL_SUSPICIOUS = MailTemplates.PAYMENT_SUSPICIOUS;
    public static final String LINK_PAYMENTS = FrontendPaths.MY_PAYMENTS;
    public static final String LINK_ADMIN_ORDERS = "/admin/orders";
    static final String INTENT_DREAM_IDS = "dreamIds";

    static final String REASON_PROVIDER_FAILED = "PROVIDER_REPORTED_FAILURE";
    static final String REASON_AMOUNT_MISMATCH = "AMOUNT_OR_CURRENCY_MISMATCH";
    static final String REASON_COUNTRY_MISMATCH = "CARD_COUNTRY_MISMATCH";
    static final String REASON_CHECKOUT_FAILED = "CHECKOUT_CREATION_FAILED";

    /** What {@link #confirm} did (for logs and tests). */
    public enum ConfirmOutcome { SUCCESS, SUSPICIOUS, FAILED, DUPLICATE, IGNORED }

    private final OrderRepository orderRepository;
    private final DreamPackageRepository packageRepository;
    private final UserRepository userRepository;
    private final PriceResolver priceResolver;
    private final PromotionService promotionService;
    private final CouponService couponService;
    private final ProviderRegistry providerRegistry;
    private final CreditService creditService;
    private final DreamService dreamService;
    private final NotificationService notificationService;
    private final InterpreterDirectory interpreterDirectory;
    private final MailService mailService;
    private final AuditService auditService;
    private final SettingsService settingsService;
    private final AfterCommit afterCommit;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @PersistenceContext
    private EntityManager entityManager;

    // ================================================================== checkout

    /**
     * Prices the package for the user's country, persists an INITIATED order and creates the provider
     * checkout. Deliberately not one transaction: the provider HTTP call must not hold DB locks.
     */
    public CheckoutResponse checkout(UUID userId, CheckoutRequest request, ResolvedCountry requestCountry) {
        User user = userRepository.findById(userId).orElseThrow(() -> NotFoundException.of("User", userId));
        DreamPackage pkg = packageRepository.findById(request.packageId())
                .filter(DreamPackage::isActive)
                .orElseThrow(() -> NotFoundException.of("Package", request.packageId()));

        String countryCode;
        CountrySource countrySource;
        if (user.getCountryCode() != null && !user.getCountryCode().isBlank()) {
            countryCode = user.getCountryCode().trim();
            countrySource = user.getCountrySource() == null ? CountrySource.IP : user.getCountrySource();
        } else {
            countryCode = requestCountry.countryCode();
            countrySource = requestCountry.source();
        }

        PriceResolver.Resolved resolved = priceResolver.resolve(countryCode, pkg.getId())
                .orElseThrow(() -> new ValidationException("No price configured for this package", "NO_PRICE"));
        Country country = priceResolver.findCountry(countryCode).orElse(null);
        PromotionService.Applied applied = promotionService.apply(pkg, resolved.price(), country);

        BigDecimal amount = applied.price();
        Coupon coupon = null;
        if (request.couponCode() != null && !request.couponCode().isBlank()) {
            coupon = couponService.validate(request.couponCode(), userId);
            amount = CouponService.apply(coupon, amount);
        }
        BigDecimal discount = resolved.price().subtract(amount);
        if (discount.signum() < 0) {
            discount = BigDecimal.ZERO;
        }

        PaymentProvider provider = providerRegistry.forCurrency(resolved.currency());
        Instant now = clock.instant();

        Order order = new Order();
        order.setUserId(userId);
        order.setProductType(ProductType.DREAMS);
        order.setPackageId(pkg.getId());
        order.setPackageNameSnapshot(user.getLocale() == Locale.EN ? pkg.getNameEn() : pkg.getNameAr());
        order.setCredits(pkg.getCredits() + applied.bonusCredits());
        order.setAmount(amount);
        order.setCurrency(resolved.currency());
        order.setDiscount(discount);
        order.setCouponId(coupon == null ? null : coupon.getId());
        order.setPromotionId(applied.promotion() == null ? null : applied.promotion().getId());
        order.setPriceRuleScope(resolved.scope());
        order.setProvider(provider.type());
        order.setStatus(OrderStatus.INITIATED);
        order.setCheckoutIntent(intentJson(request.dreamIds()));
        order.setCountryCode(countryCode);
        order.setCountrySource(countrySource);
        order.setCreatedAt(now);
        order.setExpiresAt(now.plus(Duration.ofMinutes(settingsService.getInt(SettingKeys.ORDERS_EXPIRE_MINUTES))));
        order = orderRepository.save(order);

        CheckoutSession session;
        try {
            session = provider.createCheckout(order, user);
        } catch (RuntimeException e) {
            order.setStatus(OrderStatus.FAILED);
            order.setFailureReason(REASON_CHECKOUT_FAILED);
            orderRepository.save(order);
            throw e;
        }
        order.setProviderOrderId(session.providerOrderId());
        order = orderRepository.save(order);

        return new CheckoutResponse(order.getId(), OrderDto.displayProvider(order), session.checkoutUrl(),
                order.getAmount(), order.getCurrency(), order.getExpiresAt());
    }

    // ================================================================== confirmation

    @Transactional
    public ConfirmOutcome confirm(WebhookEvent event) {
        if (event.providerTxnId() != null && orderRepository.findByProviderTxnId(event.providerTxnId()).isPresent()) {
            log.info("Duplicate payment webhook txn={} ignored", event.providerTxnId());
            return ConfirmOutcome.DUPLICATE;
        }
        Optional<Order> found = Optional.empty();
        if (event.orderId() != null) {
            found = orderRepository.findById(event.orderId());
        }
        if (found.isEmpty() && event.providerOrderId() != null) {
            found = orderRepository.findByProviderOrderId(event.providerOrderId());
        }
        if (found.isEmpty()) {
            log.warn("Payment webhook for unknown order (orderId={}, providerOrderId={})", event.orderId(),
                    event.providerOrderId());
            return ConfirmOutcome.IGNORED;
        }
        Order order = found.get();
        // lock and reload: a concurrent webhook for the same order waits here and then sees the final state
        entityManager.refresh(order, LockModeType.PESSIMISTIC_WRITE);

        if (event.providerTxnId() != null && event.providerTxnId().equals(order.getProviderTxnId())) {
            return ConfirmOutcome.DUPLICATE;
        }
        if (order.getStatus() == OrderStatus.SUCCESS || order.getStatus() == OrderStatus.SUSPICIOUS
                || order.getStatus() == OrderStatus.REFUNDED) {
            log.info("Webhook txn={} for order {} already in final state {}", event.providerTxnId(), order.getId(),
                    order.getStatus());
            return ConfirmOutcome.IGNORED;
        }

        OrderDto before = OrderDto.from(order);
        if (!event.success()) {
            order.setStatus(OrderStatus.FAILED);
            order.setFailureReason(REASON_PROVIDER_FAILED);
            order.setProviderTxnId(event.providerTxnId());
            orderRepository.save(order);
            return ConfirmOutcome.FAILED;
        }

        order.setCardCountry(event.cardCountry());
        boolean amountMatches = event.amount() != null && order.getAmount().compareTo(event.amount()) == 0
                && event.currency() != null && order.getCurrency().name().equalsIgnoreCase(event.currency().trim());
        if (!amountMatches) {
            return markSuspicious(order, event, before, REASON_AMOUNT_MISMATCH);
        }
        if (event.cardCountry() != null && !event.cardCountry().isBlank() && order.getCountryCode() != null
                && !order.getCountryCode().trim().equalsIgnoreCase(event.cardCountry().trim())) {
            return markSuspicious(order, event, before, REASON_COUNTRY_MISMATCH);
        }

        order.setStatus(OrderStatus.SUCCESS);
        order.setPaidAt(clock.instant());
        order.setProviderTxnId(event.providerTxnId());
        order.setFailureReason(null);
        orderRepository.save(order);

        creditService.add(order.getUserId(), order.getCredits(), LedgerReason.PURCHASE, order.getId(), null, null);
        couponService.redeem(order.getCouponId(), order.getUserId(), order.getId());
        promotionService.recordUse(order.getPromotionId());

        List<UUID> intent = readIntent(order.getCheckoutIntent());
        if (!intent.isEmpty()) {
            List<UUID> submitted = dreamService.autoSubmit(order.getUserId(), intent, order.getId());
            log.info("Order {} auto-submitted {} of {} intended drafts", order.getId(), submitted.size(), intent.size());
        }

        UUID orderId = order.getId();
        afterCommit.run("payment-success:" + orderId, () -> notifyPaid(orderId));
        return ConfirmOutcome.SUCCESS;
    }

    private ConfirmOutcome markSuspicious(Order order, WebhookEvent event, OrderDto before, String reason) {
        order.setStatus(OrderStatus.SUSPICIOUS);
        order.setFailureReason(reason);
        order.setProviderTxnId(event.providerTxnId());
        orderRepository.save(order);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("order", OrderDto.from(order));
        after.put("reason", reason);
        after.put("receivedAmount", event.amount());
        after.put("receivedCurrency", event.currency());
        after.put("cardCountry", event.cardCountry());
        auditService.record(null, "ORDER_SUSPICIOUS", "orders", order.getId().toString(), before, after);
        log.warn("Order {} marked SUSPICIOUS: {}", order.getId(), reason);

        UUID orderId = order.getId();
        BigDecimal receivedAmount = event.amount();
        String receivedCurrency = event.currency();
        String cardCountry = event.cardCountry();
        afterCommit.run("payment-suspicious:" + orderId,
                () -> notifySuspicious(orderId, reason, receivedAmount, receivedCurrency, cardCountry));
        return ConfirmOutcome.SUSPICIOUS;
    }

    // ================================================================== expiry

    @Transactional
    public int expireOverdue() {
        List<Order> overdue = orderRepository.findByStatusAndExpiresAtBefore(OrderStatus.INITIATED, clock.instant());
        for (Order o : overdue) {
            o.setStatus(OrderStatus.EXPIRED);
        }
        orderRepository.saveAll(overdue);
        return overdue.size();
    }

    // ================================================================== side effects (after commit)

    private void notifyPaid(UUID orderId) {
        Order order = orderRepository.findById(orderId).orElseThrow();
        User user = userRepository.findById(order.getUserId()).orElseThrow();
        String orderRef = order.getProviderTxnId() == null ? order.getId().toString() : order.getProviderTxnId();
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("orderId", order.getId().toString());
        args.put("orderRef", orderRef);
        args.put("credits", order.getCredits());
        args.put("packageName", order.getPackageNameSnapshot());
        args.put("amount", order.getAmount());
        args.put("currency", order.getCurrency().name());
        args.put("userName", user.getName());
        notificationService.notify(user.getId(), NotificationType.PAYMENT_SUCCESS, args, LINK_PAYMENTS);

        Map<String, Object> model = new LinkedHashMap<>(args);
        model.put("name", user.getName());
        model.put("paidAt", order.getPaidAt());
        model.put("link", LINK_PAYMENTS);
        mailService.send(user.getId(), user.getEmail(), MAIL_RECEIPT, user.getLocale(), model,
                "receipt:" + order.getId());
    }

    private void notifySuspicious(UUID orderId, String reason, BigDecimal receivedAmount, String receivedCurrency,
                                  String cardCountry) {
        Order order = orderRepository.findById(orderId).orElseThrow();
        User payer = userRepository.findById(order.getUserId()).orElse(null);
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("orderId", order.getId().toString());
        base.put("orderRef", order.getId().toString());
        base.put("reason", reason);
        // amount/currency as reported by the provider; expected* as stored on the order
        base.put("amount", receivedAmount);
        base.put("currency", receivedCurrency);
        base.put("expectedAmount", order.getAmount());
        base.put("expectedCurrency", order.getCurrency().name());
        base.put("orderCountry", order.getCountryCode());
        base.put("cardCountry", cardCountry);
        base.put("userName", payer == null ? "" : payer.getName());
        base.put("userEmail", payer == null ? "" : payer.getEmail());
        base.put("link", LINK_ADMIN_ORDERS);
        for (User interpreter : userRepository.findAllById(interpreterDirectory.interpreterUserIds())) {
            Map<String, Object> model = new LinkedHashMap<>(base);
            model.put("name", interpreter.getName());
            mailService.send(interpreter.getId(), interpreter.getEmail(), MAIL_SUSPICIOUS, interpreter.getLocale(),
                    model, "suspicious:" + order.getId() + ":" + interpreter.getId());
        }
    }

    // ================================================================== checkout_intent

    private String intentJson(List<UUID> dreamIds) {
        List<UUID> ids = dreamIds == null ? List.of() : new ArrayList<>(new LinkedHashSet<>(dreamIds));
        if (ids.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(Map.of(INTENT_DREAM_IDS, ids));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize checkout intent", e);
        }
    }

    List<UUID> readIntent(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            // tolerate a double-encoded JSON string
            if (node.isTextual()) {
                node = objectMapper.readTree(node.asText());
            }
            List<UUID> ids = new ArrayList<>();
            for (JsonNode n : node.path(INTENT_DREAM_IDS)) {
                try {
                    ids.add(UUID.fromString(n.asText()));
                } catch (IllegalArgumentException ignored) {
                    // skip malformed ids
                }
            }
            return ids;
        } catch (JsonProcessingException e) {
            log.warn("Unreadable checkout_intent: {}", e.getMessage());
            return List.of();
        }
    }
}
