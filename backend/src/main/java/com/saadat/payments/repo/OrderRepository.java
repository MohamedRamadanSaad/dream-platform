package com.saadat.payments.repo;

import com.saadat.common.domain.OrderStatus;
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
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Orders. JPQL entity name is {@code PaymentOrder}. */
public interface OrderRepository extends JpaRepository<Order, UUID> {

    Optional<Order> findByProviderTxnId(String providerTxnId);

    Optional<Order> findByProviderOrderId(String providerOrderId);

    /** Expiry job: INITIATED orders past expires_at. */
    List<Order> findByStatusAndExpiresAtBefore(OrderStatus status, Instant before);

    Page<Order> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    List<Order> findByUserIdOrderByCreatedAtDesc(UUID userId);

    Optional<Order> findByIdAndUserId(UUID id, UUID userId);

    Page<Order> findAllByOrderByCreatedAtDesc(Pageable pageable);

    List<Order> findByUserIdAndStatus(UUID userId, OrderStatus status);

    long countByStatus(OrderStatus status);

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

    /** [userId, currency, sum(amount)] of orders with the given statuses. */
    @Query("select o.userId, o.currency, sum(o.amount) from PaymentOrder o "
            + "where o.status in :statuses group by o.userId, o.currency")
    List<Object[]> sumAmountByUserAndCurrency(@Param("statuses") Collection<OrderStatus> statuses);
}
