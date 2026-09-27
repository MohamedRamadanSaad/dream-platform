package com.saadat.payments.domain;

import com.saadat.common.domain.CountrySource;
import com.saadat.common.domain.Currency;
import com.saadat.common.domain.OrderStatus;
import com.saadat.common.domain.PaymentProviderType;
import com.saadat.common.domain.PriceScope;
import com.saadat.common.domain.ProductType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Row of {@code orders}. JPA entity name is {@code PaymentOrder} (ORDER is an SQL/JPQL keyword): write
 * JPQL as {@code select o from PaymentOrder o}. Amounts are computed server-side only.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity(name = "PaymentOrder")
@Table(name = "orders")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "product_type", nullable = false, length = 16)
    private ProductType productType = ProductType.DREAMS;

    @Column(name = "package_id")
    private UUID packageId;

    @Column(name = "package_name_snapshot", nullable = false, length = 200)
    private String packageNameSnapshot;

    @Column(name = "credits", nullable = false)
    private int credits;

    /** Final amount to charge (after promotion + coupon). */
    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "currency", columnDefinition = "char(3)", nullable = false)
    private Currency currency;

    @Column(name = "discount", nullable = false, precision = 12, scale = 2)
    private BigDecimal discount = BigDecimal.ZERO;

    @Column(name = "coupon_id")
    private UUID couponId;

    @Column(name = "promotion_id")
    private UUID promotionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "price_rule_scope", length = 16)
    private PriceScope priceRuleScope;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 16)
    private PaymentProviderType provider;

    @Column(name = "provider_order_id", length = 128)
    private String providerOrderId;

    /** Unique — the idempotency key of webhooks. */
    @Column(name = "provider_txn_id", length = 128)
    private String providerTxnId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private OrderStatus status = OrderStatus.INITIATED;

    @Column(name = "failure_reason", columnDefinition = "text")
    private String failureReason;

    /** Raw JSON, e.g. {"dreamIds":["..."]}. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "checkout_intent", columnDefinition = "jsonb")
    private String checkoutIntent;

    @Column(name = "country_code", columnDefinition = "char(2)")
    private String countryCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "country_source", length = 16)
    private CountrySource countrySource;

    @Column(name = "card_country", length = 8)
    private String cardCountry;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
