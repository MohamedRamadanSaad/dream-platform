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
import java.time.Clock;
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
    /** Bookings never paid (tab UNPAID, V41). */
    static final List<OrderStatus> UNPAID_STATUSES = List.of(OrderStatus.INITIATED, OrderStatus.EXPIRED);

    private final OrderRepository orderRepository;
    private final UserRepository userRepository;
    private final DreamRepository dreamRepository;
    private final CreditLedgerRepository ledgerRepository;
    private final CouponRepository couponRepository;
    private final DreamAttachments dreamAttachments;
    private final OrderCredits orderCredits;
    private final com.saadat.credits.service.CreditService creditService;
    private final Clock clock;

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

    @Transactional(readOnly = true)
    public PageResponse<AdminOrderRow> admin(AdminOrderDtos.Scope scope, String q, Integer page, Integer size) {
        return admin(scope, q, AdminOrderFilter.none(), page, size);
    }

    /**
     * One tab of the interpreter's payments page, optionally narrowed by {@code q} = order number (ignoring case,
     * with or without the prefix: "15001", "rn-15001"; a {@code q} that is not an order number finds nothing) and by
     * the filters (docs/PAYMENTS_FILTERS_CONTRACT.md §3). Example: {@code ?scope=REVIEW&q=15001&currency=EGP}.
     */
    @Transactional(readOnly = true)
    public PageResponse<AdminOrderRow> admin(AdminOrderDtos.Scope scope, String q, AdminOrderFilter filter,
                                             Integer page, Integer size) {
        AdminOrderDtos.Scope s = scope == null ? AdminOrderDtos.Scope.ALL : scope;
        Instant now = clock.instant();
        Optional<Specification<Order>> byNumber = numberSpec(q);
        if (byNumber == null) {
            Pageable p = Pages.of(page, size);
            return PageResponse.of(List.<AdminOrderRow>of(), p.getPageNumber(), p.getPageSize(), 0);
        }
        Specification<Order> spec = scopeSpec(s).and(filterSpec(filter, now));
        if (byNumber.isPresent()) {
            spec = spec.and(byNumber.get());
        }
        Sort sort = s == AdminOrderDtos.Scope.REVIEW
                ? Sort.by(Sort.Order.asc("transferSubmittedAt"), Sort.Order.asc("createdAt"))
                : Sort.by(Sort.Direction.DESC, "createdAt");
        Page<Order> orders = orderRepository.findAll(spec, Pages.of(page, size, sort));
        List<UUID> userIds = orders.getContent().stream().map(Order::getUserId).distinct().toList();
        Map<UUID, User> users = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity(), (a, b) -> a));
        Map<UUID, Long> held = heldCounts(orders.getContent().stream().map(Order::getId).toList());
        Map<UUID, Instant> lastPaid = new java.util.HashMap<>();
        if (!userIds.isEmpty()) {
            for (Object[] row : orderRepository.lastCreatedByUserAndStatus(userIds, OrderStatus.SUCCESS)) {
                lastPaid.put((UUID) row[0], (Instant) row[1]);
            }
        }
        Function<Order, AdminOrderRow> mapper = o -> {
            User u = users.get(o.getUserId());
            return AdminOrderRow.of(OrderDto.from(o).withStatus(shownStatus(o, now)), o.getUserId(),
                    u == null ? "" : u.getName(),
                    u == null ? "" : u.getEmail(), o.getSettledAmountEgp(), o.getTransferReference(),
                    o.getTransferSenderName(), o.getTransferSubmittedAt(),
                    dreamAttachments.readIntent(o.getCheckoutIntent()).size(), o.getFailureReason(),
                    o.getApprovalRevokedAt(), o.getRevokeNote(), held.getOrDefault(o.getId(), 0L).intValue(),
                    o.getExpiresAt(), o.getPackageId(), o.getLeftoverWithdrawnAt(),
                    lastPaid.get(o.getUserId()) != null && o.getCreatedAt() != null
                            && lastPaid.get(o.getUserId()).isAfter(o.getCreatedAt()),
                    o.getLeftoverWithdrawnCredits(), o.getLeftoverReason(), o.getLeftoverNote());
        };
        return PageResponse.from(orders, mapper);
    }

    /** An INITIATED order past its expiry is shown (and filtered) as EXPIRED before the expiry job runs (V41). */
    static OrderStatus shownStatus(Order o, Instant now) {
        if (o.getStatus() == OrderStatus.INITIATED && o.getExpiresAt() != null && !o.getExpiresAt().isAfter(now)) {
            return OrderStatus.EXPIRED;
        }
        return o.getStatus();
    }

    /** The rows of a tab. DOMESTIC / INTERNATIONAL leave out the unpaid bookings (they live in UNPAID, V41). */
    static Specification<Order> scopeSpec(AdminOrderDtos.Scope scope) {
        return switch (scope) {
            case REVIEW -> (root, query, cb) -> cb.and(
                    cb.equal(root.get("provider"), PaymentProviderType.INSTAPAY),
                    cb.equal(root.get("status"), OrderStatus.PENDING_REVIEW));
            case DOMESTIC -> (root, query, cb) -> cb.and(
                    cb.equal(root.get("countryCode"), DOMESTIC_COUNTRY),
                    cb.not(unpaid(root, cb)));
            case INTERNATIONAL -> (root, query, cb) -> cb.and(
                    cb.or(cb.isNull(root.get("countryCode")), cb.notEqual(root.get("countryCode"), DOMESTIC_COUNTRY)),
                    cb.not(unpaid(root, cb)));
            case UNPAID -> (root, query, cb) -> unpaid(root, cb);
            case ALL -> (root, query, cb) -> cb.conjunction();
        };
    }

    /**
     * A booking never paid: INITIATED, EXPIRED, or FAILED at the gateway (a failure reason other than the
     * interpreter's TRANSFER_REJECTED / APPROVAL_REVOKED, or none).
     */
    private static jakarta.persistence.criteria.Predicate unpaid(jakarta.persistence.criteria.Root<Order> root,
                                                                jakarta.persistence.criteria.CriteriaBuilder cb) {
        return cb.or(
                root.get("status").in(UNPAID_STATUSES),
                cb.and(cb.equal(root.get("status"), OrderStatus.FAILED),
                        cb.or(cb.isNull(root.get("failureReason")),
                                cb.not(root.get("failureReason").in(InstapayService.REASON_REJECTED,
                                        InstapayService.REASON_REVOKED)))));
    }

    private static Specification<Order> filterSpec(AdminOrderFilter filter, Instant now) {
        return (filter == null ? AdminOrderFilter.none() : filter).toSpec(now);
    }

    /** Empty = no search; null = a {@code q} that is not an order number (finds nothing). */
    private static Optional<Specification<Order>> numberSpec(String q) {
        if (q == null || q.isBlank()) {
            return Optional.empty();
        }
        Optional<OrderNumbers.Query> parsed = OrderNumbers.parseQuery(q);
        if (parsed.isEmpty()) {
            return null;
        }
        OrderNumbers.Query number = parsed.get();
        Specification<Order> spec = number.hasPrefix()
                ? (root, query, cb) -> cb.equal(cb.upper(root.<String>get("orderNumber")), number.number())
                : (root, query, cb) -> cb.like(root.<String>get("orderNumber"), "%-" + number.digits());
        return Optional.of(spec);
    }

    /** Held (AWAITING_PAYMENT) dreams per order (V40). */
    private Map<UUID, Long> heldCounts(List<UUID> orderIds) {
        Map<UUID, Long> out = new java.util.HashMap<>();
        if (orderIds.isEmpty()) {
            return out;
        }
        for (Object[] row : dreamRepository.countHeldByOrder(orderIds, DreamStatus.AWAITING_PAYMENT)) {
            out.put((UUID) row[0], ((Number) row[1]).longValue());
        }
        return out;
    }

    @Transactional(readOnly = true)
    public AdminOrderDtos.Counts counts() {
        return counts(AdminOrderFilter.none(), null);
    }

    /** Tab badges; with filters (and {@code q}) they match the filtered view (V41). */
    @Transactional(readOnly = true)
    public AdminOrderDtos.Counts counts(AdminOrderFilter filter, String q) {
        Optional<Specification<Order>> byNumber = numberSpec(q);
        if (byNumber == null) {
            return new AdminOrderDtos.Counts(0, 0, 0, 0);
        }
        // a tab's own filters (status, flag) would empty the other tabs' badges: the counts leave them out
        Specification<Order> common = filterSpec(filter == null ? null : filter.withoutTabFilters(), clock.instant());
        if (byNumber.isPresent()) {
            common = common.and(byNumber.get());
        }
        return new AdminOrderDtos.Counts(
                orderRepository.count(scopeSpec(AdminOrderDtos.Scope.REVIEW).and(common)),
                orderRepository.count(scopeSpec(AdminOrderDtos.Scope.DOMESTIC).and(common)),
                orderRepository.count(scopeSpec(AdminOrderDtos.Scope.INTERNATIONAL).and(common)),
                orderRepository.count(scopeSpec(AdminOrderDtos.Scope.UNPAID).and(common)));
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
        // the dreams it currently pays for (debits not given back): one already interpreted blocks the revoke
        Set<UUID> paid = orderCredits.openDebits(o.getUserId(), o.getId()).stream()
                .map(CreditLedgerEntry::getDreamId).collect(Collectors.toSet());
        int interpretedDreams = paid.isEmpty() ? 0 : (int) dreamRepository.findAllById(paid).stream()
                .filter(d -> d.getStatus() == DreamStatus.INTERPRETED).count();
        if (revocable && interpretedDreams > 0) {
            revokeBlockedReason = InstapayService.CODE_DREAM_ALREADY_INTERPRETED;
        }
        int leftover = leftoverCredits(o);
        return new AdminOrderDtos.Detail(o.getId(), o.getOrderNumber(), shownStatus(o, clock.instant()),
                o.getFailureReason(), OrderDto.displayProvider(o),
                o.getProviderTxnId(), o.getPackageNameSnapshot(), o.getCredits(), o.getAmount(), o.getCurrency(),
                o.getSettledAmountEgp(), o.getDiscount(), couponCode, o.getCountryCode(), o.getCountrySource(),
                o.getCreatedAt(), o.getPaidAt(), o.getExpiresAt(), transfer, user, dreams, canReview,
                revocable && revokeBlockedReason == null, revokeBlockedReason, o.getApprovalRevokedAt(),
                o.getRevokeNote(), heldCounts(List.of(o.getId())).getOrDefault(o.getId(), 0L).intValue(),
                leftover, interpretedDreams, o.getStatus() == OrderStatus.SUCCESS && leftover > 0,
                o.getLeftoverWithdrawnAt(), o.getLeftoverWithdrawnCredits(), o.getLeftoverNote(),
                o.getLeftoverReason(), o.getStatus() == OrderStatus.SUCCESS
                        && !creditService.restorableLeftover(o.getUserId(), o.getId()).isEmpty());
    }

    /** Unused, unexpired credits of the order's lots right now (0 for an unpaid order, V41). */
    int leftoverCredits(Order o) {
        OrderCredits.Usage usage = orderCredits.of(o.getUserId(), o.getId());
        return usage == null ? 0 : usage.remaining();
    }
}
