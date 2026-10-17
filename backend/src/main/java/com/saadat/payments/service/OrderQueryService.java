package com.saadat.payments.service;

import com.saadat.common.api.PageResponse;
import com.saadat.common.api.Pages;
import com.saadat.common.domain.DreamStatus;
import com.saadat.common.domain.LedgerReason;
import com.saadat.common.domain.OrderStatus;
import com.saadat.common.domain.PaymentProviderType;
import com.saadat.common.error.NotFoundException;
import com.saadat.common.util.Ages;
import com.saadat.credits.service.OrderCredits;
import com.saadat.dreams.domain.Dream;
import com.saadat.dreams.repo.DreamRepository;
import com.saadat.payments.api.AdminOrderDtos;
import com.saadat.payments.api.AdminOrderRow;
import com.saadat.payments.api.OrderDto;
import com.saadat.payments.domain.CreditLedgerEntry;
import com.saadat.payments.domain.Order;
import com.saadat.payments.repo.CreditLedgerRepository;
import com.saadat.payments.repo.OrderRepository;
import com.saadat.pricing.domain.Coupon;
import com.saadat.pricing.repo.CouponRepository;
import com.saadat.users.domain.User;
import com.saadat.users.repo.UserRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read side of orders: /me/orders, /me/orders/{id}, /admin/orders (tabs, order-number search, counts, order page). */
@Service
@RequiredArgsConstructor
public class OrderQueryService {

    /** Domestic = Egypt (the only country paying by InstaPay transfer). */
    public static final String DOMESTIC_COUNTRY = "EG";

    private final OrderRepository orderRepository;
    private final UserRepository userRepository;
    private final DreamRepository dreamRepository;
    private final CreditLedgerRepository ledgerRepository;
    private final CouponRepository couponRepository;
    private final DreamAttachments dreamAttachments;
    private final OrderCredits orderCredits;

    @Transactional(readOnly = true)
    public PageResponse<OrderDto> mine(UUID userId, Integer page, Integer size) {
        return PageResponse.from(orderRepository.findByUserIdOrderByCreatedAtDesc(userId, Pages.of(page, size)),
                OrderDto::from);
    }

    @Transactional(readOnly = true)
    public OrderDto mine(UUID userId, UUID orderId) {
        return orderRepository.findByIdAndUserId(orderId, userId).map(OrderDto::from)
                .orElseThrow(() -> NotFoundException.of("Order", orderId));
    }

    @Transactional(readOnly = true)
    public List<OrderDto> ofUser(UUID userId) {
        return orderRepository.findByUserIdOrderByCreatedAtDesc(userId).stream().map(OrderDto::from).toList();
    }

    /** Every order, newest first (the CSV export). */
    @Transactional(readOnly = true)
    public PageResponse<AdminOrderRow> all(Integer page, Integer size) {
        return admin(AdminOrderDtos.Scope.ALL, null, page, size);
    }

    @Transactional(readOnly = true)
    public PageResponse<AdminOrderRow> all(Integer page, Integer size, String q) {
        return admin(AdminOrderDtos.Scope.ALL, q, page, size);
    }

    /**
     * One tab of the interpreter's payments page (contract §3), optionally narrowed by {@code q} = order number,
     * ignoring case and with or without the prefix ("15001", "rn-15001", "RN-15001"); a {@code q} that is not an order
     * number finds nothing. Example: {@code ?scope=REVIEW&q=15001}.
     */
    @Transactional(readOnly = true)
    public PageResponse<AdminOrderRow> admin(AdminOrderDtos.Scope scope, String q, Integer page, Integer size) {
        AdminOrderDtos.Scope s = scope == null ? AdminOrderDtos.Scope.ALL : scope;
        Specification<Order> spec = switch (s) {
            case REVIEW -> (root, query, cb) -> cb.and(
                    cb.equal(root.get("provider"), PaymentProviderType.INSTAPAY),
                    cb.equal(root.get("status"), OrderStatus.PENDING_REVIEW));
            case DOMESTIC -> (root, query, cb) -> cb.equal(root.get("countryCode"), DOMESTIC_COUNTRY);
            case INTERNATIONAL -> (root, query, cb) -> cb.or(
                    cb.isNull(root.get("countryCode")),
                    cb.notEqual(root.get("countryCode"), DOMESTIC_COUNTRY));
            case ALL -> (root, query, cb) -> cb.conjunction();
        };
        if (q != null && !q.isBlank()) {
            Optional<OrderNumbers.Query> parsed = OrderNumbers.parseQuery(q);
            if (parsed.isEmpty()) {
                Pageable p = Pages.of(page, size);
                return PageResponse.of(List.<AdminOrderRow>of(), p.getPageNumber(), p.getPageSize(), 0);
            }
            OrderNumbers.Query number = parsed.get();
            Specification<Order> byNumber = number.hasPrefix()
                    ? (root, query, cb) -> cb.equal(cb.upper(root.<String>get("orderNumber")), number.number())
                    : (root, query, cb) -> cb.like(root.<String>get("orderNumber"), "%-" + number.digits());
            spec = spec.and(byNumber);
        }
        Sort sort = s == AdminOrderDtos.Scope.REVIEW
                ? Sort.by(Sort.Order.asc("transferSubmittedAt"), Sort.Order.asc("createdAt"))
                : Sort.by(Sort.Direction.DESC, "createdAt");
        Page<Order> orders = orderRepository.findAll(spec, Pages.of(page, size, sort));
        List<UUID> userIds = orders.getContent().stream().map(Order::getUserId).distinct().toList();
        Map<UUID, User> users = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity(), (a, b) -> a));
        Function<Order, AdminOrderRow> mapper = o -> {
            User u = users.get(o.getUserId());
            return AdminOrderRow.of(OrderDto.from(o), o.getUserId(), u == null ? "" : u.getName(),
                    u == null ? "" : u.getEmail(), o.getSettledAmountEgp(), o.getTransferReference(),
                    o.getTransferSenderName(), o.getTransferSubmittedAt(),
                    dreamAttachments.readIntent(o.getCheckoutIntent()).size());
        };
        return PageResponse.from(orders, mapper);
    }

    @Transactional(readOnly = true)
    public AdminOrderDtos.Counts counts() {
        return new AdminOrderDtos.Counts(
                orderRepository.countByProviderAndStatus(PaymentProviderType.INSTAPAY, OrderStatus.PENDING_REVIEW),
                orderRepository.countByCountryCode(DOMESTIC_COUNTRY),
                orderRepository.countOutsideCountry(DOMESTIC_COUNTRY));
    }

    /**
     * The order page: buyer, transfer + decision, and its dreams = the dreams of its checkout intent plus the dreams
     * paid with its credits (SUBMIT ledger rows of this order), with their full text.
     */
    @Transactional(readOnly = true)
    public AdminOrderDtos.Detail detail(UUID orderId) {
        Order o = orderRepository.findById(orderId).orElseThrow(() -> NotFoundException.of("Order", orderId));
        User buyer = userRepository.findById(o.getUserId()).orElse(null);

        AdminOrderDtos.Transfer transfer = null;
        if (o.getTransferReference() != null) {
            String reviewer = o.getReviewedBy() == null ? null
                    : userRepository.findById(o.getReviewedBy()).map(User::getName).orElse(null);
            transfer = new AdminOrderDtos.Transfer(o.getTransferReference(), o.getTransferSenderName(),
                    o.getTransferSubmittedAt(), o.getReviewedAt(), reviewer, o.getReviewNote());
        }

        Set<UUID> dreamIds = new LinkedHashSet<>(dreamAttachments.readIntent(o.getCheckoutIntent()));
        for (CreditLedgerEntry e : ledgerRepository.findAllByOrderIdAndReasonOrderByCreatedAtAsc(o.getId(),
                LedgerReason.SUBMIT)) {
            if (e.getDreamId() != null) {
                dreamIds.add(e.getDreamId());
            }
        }
        // dreams waiting for this payment to be approved again (V40)
        dreamRepository.findByHeldOrderId(o.getId()).forEach(d -> dreamIds.add(d.getId()));
        List<AdminOrderDtos.DreamRef> dreams = new ArrayList<>();
        List<Dream> rows = List.of();
        if (!dreamIds.isEmpty()) {
            // only the buyer's dreams (an intent is never trusted beyond that); soft-deleted dreams are not loaded
            rows = dreamRepository.findAllById(dreamIds).stream()
                    .filter(d -> Objects.equals(d.getUserId(), o.getUserId()))
                    .sorted(Comparator.comparing(Dream::getCreatedAt, Comparator.nullsLast(Comparator.<Instant>naturalOrder())))
                    .toList();
            for (Dream d : rows) {
                dreams.add(new AdminOrderDtos.DreamRef(d.getId(), d.getStatus(), d.getText(), d.getCreatedAt(),
                        d.getSubmittedAt()));
            }
        }

        String couponCode = o.getCouponId() == null ? null
                : couponRepository.findById(o.getCouponId()).map(Coupon::getCode).orElse(null);
        AdminOrderDtos.UserRef user = buyer == null
                ? new AdminOrderDtos.UserRef(o.getUserId(), "", "", null, null)
                : new AdminOrderDtos.UserRef(buyer.getId(), buyer.getName(), buyer.getEmail(), buyer.getCountryCode(),
                        Ages.of(buyer.getBirthDate()));
        boolean canReview = o.getProvider() == PaymentProviderType.INSTAPAY && o.getStatus() == OrderStatus.PENDING_REVIEW;
        boolean revocable = o.getProvider() == PaymentProviderType.INSTAPAY && o.getStatus() == OrderStatus.SUCCESS;
        String revokeBlockedReason = null;
        if (revocable) {
            // the dreams it currently pays for (debits not given back): one already interpreted blocks the revoke
            Set<UUID> paid = orderCredits.openDebits(o.getUserId(), o.getId()).stream()
                    .map(CreditLedgerEntry::getDreamId).collect(Collectors.toSet());
            boolean interpreted = !paid.isEmpty() && dreamRepository.findAllById(paid).stream()
                    .anyMatch(d -> d.getStatus() == DreamStatus.INTERPRETED);
            if (interpreted) {
                revokeBlockedReason = InstapayService.CODE_DREAM_ALREADY_INTERPRETED;
            }
        }
        return new AdminOrderDtos.Detail(o.getId(), o.getOrderNumber(), o.getStatus(), o.getFailureReason(), OrderDto.displayProvider(o),
                o.getProviderTxnId(), o.getPackageNameSnapshot(), o.getCredits(), o.getAmount(), o.getCurrency(),
                o.getSettledAmountEgp(), o.getDiscount(), couponCode, o.getCountryCode(), o.getCountrySource(),
                o.getCreatedAt(), o.getPaidAt(), o.getExpiresAt(), transfer, user, dreams, canReview,
                revocable && revokeBlockedReason == null, revokeBlockedReason);
    }
}
