package com.saadat.pricing.repo;

import com.saadat.common.domain.PriceScope;
import com.saadat.pricing.domain.PriceRule;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PriceRuleRepository extends JpaRepository<PriceRule, UUID> {

    /** All rules of a package — PriceResolver picks COUNTRY → GROUP → CONTINENT → GLOBAL in memory. */
    List<PriceRule> findByPackageId(UUID packageId);

    /**
     * Upsert lookup. Spring Data renders a null {@code scopeId} as {@code IS NULL}, so this also works for
     * GLOBAL; {@link #findByScopeAndScopeIdIsNullAndPackageId} is the explicit variant.
     */
    Optional<PriceRule> findByScopeAndScopeIdAndPackageId(PriceScope scope, String scopeId, UUID packageId);

    Optional<PriceRule> findByScopeAndScopeIdIsNullAndPackageId(PriceScope scope, UUID packageId);

    List<PriceRule> findByScopeAndScopeId(PriceScope scope, String scopeId);

    List<PriceRule> findAllByOrderByScopeAscScopeIdAsc();
}
