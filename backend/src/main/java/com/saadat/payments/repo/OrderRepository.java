package com.saadat.payments.repo;

import com.saadat.common.domain.OrderStatus;
import com.saadat.common.domain.PaymentProviderType;
import com.saadat.payments.domain.Order;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** Orders. JPQL entity name is {@code PaymentOrder}. */
public interface OrderRepository extends JpaRepository<Order, UUID>, JpaSpecificationExecutor<Order> {

    Optional<Order> findByProviderTxnId(String providerTxnId);

    Optional<Order> findByProviderOrderId(String providerOrderId);

    /** Expiry job: INITIATED orders past expires_at. */
    List<Order> findByStatusAndExpiresAtBefore(OrderStatus status, Instant before);

    Page<Order> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    List<Order> findByUserIdOrderByCreatedAtDesc(UUID userId);

    Optional<Order> findByIdAndUserId(UUID id, UUID userId);

    Page<Order> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /** Next value of order_number_seq (V38; never in a read-only transaction). */
    @Transactional
    @Query(value = "select nextval('order_number_seq')", nativeQuery = true)
    Long nextOrderNumber();

    /** Admin search by the whole order number, ignoring case ({@code number} in upper case, e.g. RN-15001). */
    @Query("select o from PaymentOrder o where upper(o.orderNumber) = :number order by o.createdAt desc")
    Page<Order> searchByNumber(@Param("number") String number, Pageable pageable);

    /** Admin search by the numeric part only (e.g. 15001), whatever the prefix. */
    @Query("select o from PaymentOrder o where o.orderNumber like concat('%-', :digits) order by o.createdAt desc")
    Page<Order> searchByNumberDigits(@Param("digits") String digits, Pageable pageable);

    List<Order> findByUserIdAndStatus(UUID userId, OrderStatus status);

    /** The user's latest order in {@code status} (user dashboard: the last package bought). */
    Optional<Order> findFirstByUserIdAndStatusOrderByPaidAtDescCreatedAtDesc(UUID userId, OrderStatus status);

    long countByStatus(OrderStatus status);

    /** InstaPay review queue (oldest transfer first). */
    List<Order> findByProviderAndStatusOrderByTransferSubmittedAtAsc(PaymentProviderType provider, OrderStatus status);

    /** Recently reviewed InstaPay transfers (newest decision first). */
    List<Order> findTop50ByProviderAndStatusInAndReviewedAtIsNotNullOrderByReviewedAtDesc(
            PaymentProviderType provider, Collection<OrderStatus> statuses);

    long countByProviderAndStatus(PaymentProviderType provider, OrderStatus status);

    // ---- admin orders tabs (docs/UX_PAYMENTS_REVIEW_CONTRACT.md §3) ----
    /** Tabs of /admin/orders are built with Specifications (OrderQueryService); these are their counts. */
    long countByCountryCode(String countryCode);

    @Query("select count(o) from PaymentOrder o where o.countryCode is null or o.countryCode <> :countryCode")
    long countOutsideCountry(@Param("countryCode") String countryCode);

    /** The user's orders in {@code statuses}, newest first (dreams attached to a payment). */
    List<Order> findByUserIdAndStatusInOrderByCreatedAtDesc(UUID userId, Collection<OrderStatus> statuses);

    /** Another live order already holds this InstaPay reference (V34 unique index, checked first for a clear error). */
    boolean existsByTransferReferenceAndStatusInAndIdNot(String transferReference, Collection<OrderStatus> statuses,
                                                         UUID id);

    /** A revoked order (FAILED / APPROVAL_REVOKED) keeps its reference reserved (V40 unique index). */
    boolean existsByTransferReferenceAndStatusAndFailureReasonAndIdNot(String transferReference, OrderStatus status,
                                                                       String failureReason, UUID id);

    /** Row lock for webhook confirmation. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from PaymentOrder o where o.id = :id")
    Optional<Order> findByIdForUpdate(@Param("id") UUID id);

    /** [currency, sum(amount)] of orders with {@code status} paid since {@code since}. */
    @Query("select o.currency, sum(o.amount) from PaymentOrder o "
            + "where o.status = :status and o.paidAt >= :since group by o.currency")
    List<Object[]> sumAmountByCurrencySince(@Param("status") OrderStatus status, @Param("since") Instant since);

    /** [countryCode, currency, sum(amount)] of paid orders since {@code since}. */
    @Query("select o.countryCode, o.currency, sum(o.amount) from PaymentOrder o "
            + "where o.status = :status and o.paidAt >= :since group by o.countryCode, o.currency")
    List<Object[]> sumAmountByCountryAndCurrencySince(@Param("status") OrderStatus status,
                                                      @Param("since") Instant since);

    /** Settled EGP of orders with {@code status} paid since {@code since}; null when there is none. */
    @Query("select sum(o.settledAmountEgp) from PaymentOrder o "
            + "where o.status = :status and o.paidAt >= :since")
    java.math.BigDecimal sumSettledEgpSince(@Param("status") OrderStatus status, @Param("since") Instant since);

    /** [countryCode, sum(settledAmountEgp)] of paid orders since {@code since}. */
    @Query("select o.countryCode, sum(o.settledAmountEgp) from PaymentOrder o "
            + "where o.status = :status and o.paidAt >= :since group by o.countryCode")
    List<Object[]> sumSettledEgpByCountrySince(@Param("status") OrderStatus status, @Param("since") Instant since);

    /** [userId, currency, sum(amount)] of orders with the given statuses. */
    @Query("select o.userId, o.currency, sum(o.amount) from PaymentOrder o "
            + "where o.status in :statuses group by o.userId, o.currency")
    List<Object[]> sumAmountByUserAndCurrency(@Param("statuses") Collection<OrderStatus> statuses);
}
