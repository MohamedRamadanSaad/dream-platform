package com.saadat.pricing.repo;

import com.saadat.pricing.domain.Promotion;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface PromotionRepository extends JpaRepository<Promotion, UUID> {

    /** Active and inside its time window at {@code now} (package/scope/max-uses filtering done in the service). */
    @Query("select p from Promotion p where p.active = true and p.startsAt <= :now and p.endsAt > :now")
    List<Promotion> findActiveAt(@Param("now") Instant now);

    List<Promotion> findAllByOrderByStartsAtDesc();

    /** Atomically consumes one use; returns 0 when max_uses is already reached. */
    @Modifying
    @Transactional
    @Query("update Promotion p set p.usedCount = p.usedCount + 1 "
            + "where p.id = :id and (p.maxUses is null or p.usedCount < p.maxUses)")
    int incrementUsedCount(@Param("id") UUID id);
}
