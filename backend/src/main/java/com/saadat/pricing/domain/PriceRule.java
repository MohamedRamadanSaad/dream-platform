package com.saadat.pricing.domain;

import com.saadat.common.domain.Currency;
import com.saadat.common.domain.PriceScope;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.SoftDelete;

/**
 * Row of {@code price_rules}; unique (scope, scope_id, package_id) with NULLS NOT DISTINCT among non-deleted rows.
 * {@code scopeId}: null for GLOBAL, continent code for CONTINENT, group UUID (string) for GROUP,
 * country code for COUNTRY.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@SoftDelete(columnName = "deleted")
@Table(name = "price_rules")
public class PriceRule {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false, length = 16)
    private PriceScope scope;

    @Column(name = "scope_id", length = 64)
    private String scopeId;

    @Column(name = "package_id", nullable = false)
    private UUID packageId;

    @Column(name = "price", nullable = false, precision = 12, scale = 2)
    private BigDecimal price;

    @Enumerated(EnumType.STRING)
    @Column(name = "currency", columnDefinition = "char(3)", nullable = false)
    private Currency currency;
}
