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

    Page<CreditLedgerEntry> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    Optional<CreditLedgerEntry> findByOrderIdAndReason(UUID orderId, LedgerReason reason);

    boolean existsByOrderIdAndReason(UUID orderId, LedgerReason reason);

    Optional<CreditLedgerEntry> findByDreamIdAndReason(UUID dreamId, LedgerReason reason);

    boolean existsByDreamIdAndReason(UUID dreamId, LedgerReason reason);
}
