package com.saadat.payments.repo;

import com.saadat.common.domain.LedgerReason;
import com.saadat.payments.domain.CreditLedgerEntry;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CreditLedgerRepository extends JpaRepository<CreditLedgerEntry, UUID> {

    /** Balance = SUM(delta). For consumption, lock the user row first (UserRepository.findByIdForUpdate). */
    @Query("select coalesce(sum(l.delta), 0) from CreditLedgerEntry l where l.userId = :userId")
    int balance(@Param("userId") UUID userId);

    List<CreditLedgerEntry> findByUserIdOrderByCreatedAtDesc(UUID userId);

    /**
     * Net credits that went to dreams: the dream debits ({@code submit}, negative) plus the dream refunds
     * ({@code refund} rows linked to a dream, positive). Minus this value = credits used so far.
     */
    @Query("select coalesce(sum(l.delta), 0) from CreditLedgerEntry l where l.userId = :userId "
            + "and (l.reason = :submit or (l.reason = :refund and l.dreamId is not null))")
    long netDreamCredits(@Param("userId") UUID userId, @Param("submit") LedgerReason submit,
                         @Param("refund") LedgerReason refund);

    List<CreditLedgerEntry> findByUserIdOrderByCreatedAtAsc(UUID userId);

    /** Users owning at least one purchase that is past expiry and not settled yet (CreditExpiryJob). */
    @Query("select distinct l.userId from CreditLedgerEntry l where l.expiresAt <= :now and l.expirySettledAt is null")
    List<UUID> findUserIdsWithDueExpiry(@Param("now") java.time.Instant now);

    Page<CreditLedgerEntry> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    Optional<CreditLedgerEntry> findByOrderIdAndReason(UUID orderId, LedgerReason reason);

    boolean existsByOrderIdAndReason(UUID orderId, LedgerReason reason);

    /** Every entry of an order with {@code reason} (e.g. the SUBMIT rows of the dreams it paid for), oldest first. */
    List<CreditLedgerEntry> findAllByOrderIdAndReasonOrderByCreatedAtAsc(UUID orderId, LedgerReason reason);

    Optional<CreditLedgerEntry> findByDreamIdAndReason(UUID dreamId, LedgerReason reason);

    boolean existsByDreamIdAndReason(UUID dreamId, LedgerReason reason);

    /** A row with {@code reason} that names {@code sourceId} exists (e.g. the REFUND of a SUBMIT row, V40). */
    boolean existsBySourceIdAndReason(UUID sourceId, LedgerReason reason);

    /** Every entry of an order, oldest first. */
    List<CreditLedgerEntry> findAllByOrderIdOrderByCreatedAtAsc(UUID orderId);
}
