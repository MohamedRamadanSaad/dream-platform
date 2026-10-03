package com.saadat.payments.domain;

import com.saadat.common.domain.LedgerReason;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Row of {@code credit_ledger}. Balance = SUM(delta) per user; rows are append-only.
 * DB guards: delta != 0; at most one PURCHASE per order, one SUBMIT and one REFUND per dream.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "credit_ledger")
public class CreditLedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "delta", nullable = false)
    private int delta;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, length = 16)
    private LedgerReason reason;

    @Column(name = "order_id")
    private UUID orderId;

    @Column(name = "dream_id")
    private UUID dreamId;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "note", columnDefinition = "text")
    private String note;

    /** Purchases only: when the unused part expires (null = never). */
    @Column(name = "expires_at")
    private Instant expiresAt;

    /** EXPIRE rows only: the purchase row that expired. */
    @Column(name = "source_id")
    private UUID sourceId;

    /** Purchases only: set once the expiry job has handled this purchase (EXPIRE row written or nothing left). */
    @Column(name = "expiry_settled_at")
    private Instant expirySettledAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
