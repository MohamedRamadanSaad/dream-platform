package com.saadat.pricing.domain;

import com.saadat.common.domain.Continent;
import com.saadat.common.domain.Currency;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Row of {@code countries} (PK = ISO alpha-2 code). {@code groupId} → country_groups.id. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "countries")
public class Country {

    @Id
    @Column(name = "code", columnDefinition = "char(2)", nullable = false, updatable = false)
    private String code;

    @Column(name = "name_ar", nullable = false, length = 100)
    private String nameAr;

    @Column(name = "name_en", nullable = false, length = 100)
    private String nameEn;

    @Enumerated(EnumType.STRING)
    @Column(name = "continent", columnDefinition = "char(2)", nullable = false)
    private Continent continent;

    @Enumerated(EnumType.STRING)
    @Column(name = "default_currency", columnDefinition = "char(3)", nullable = false)
    private Currency defaultCurrency;

    @Column(name = "group_id")
    private UUID groupId;
}
