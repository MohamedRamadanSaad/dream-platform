package com.saadat.pricing.repo;

import com.saadat.pricing.domain.Coupon;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface CouponRepository extends JpaRepository<Coupon, UUID> {

    /** Codes are stored upper-case; pass {@code code.trim().toUpperCase(Locale.ROOT)}. */
    Optional<Coupon> findByCode(String code);

    boolean existsByCode(String code);

    List<Coupon> findAllByOrderByCodeAsc();

    /** Atomically consumes one use; returns 0 when max_uses is already reached. */
    @Modifying
    @Transactional
    @Query("update Coupon c set c.usedCount = c.usedCount + 1 "
            + "where c.id = :id and (c.maxUses is null or c.usedCount < c.maxUses)")
    int incrementUsedCount(@Param("id") UUID id);
}
