package com.saadat.pricing.repo;

import com.saadat.pricing.domain.CouponRedemption;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CouponRedemptionRepository extends JpaRepository<CouponRedemption, UUID> {

    /** Per-user limit check. */
    long countByCouponIdAndUserId(UUID couponId, UUID userId);

    Optional<CouponRedemption> findByOrderId(UUID orderId);

    boolean existsByOrderId(UUID orderId);
}
