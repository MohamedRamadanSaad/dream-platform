package com.saadat.dreams.domain;

import com.saadat.common.domain.DreamStatus;
import com.saadat.common.domain.Gender;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.SoftDelete;

/**
 * Row of {@code dreams}. Status machine: DRAFT → IN_REVIEW ⇄ AWAITING_USER_REPLY → INTERPRETED; CANCELLED.
 * {@code expectedBy} is the SLA deadline (admin DTO: slaDeadline); paused while AWAITING_USER_REPLY.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@SoftDelete(columnName = "deleted")
@Table(name = "dreams")
public class Dream {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "gender", nullable = false, length = 16)
    private Gender gender;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private DreamStatus status = DreamStatus.DRAFT;

    @Column(name = "text", nullable = false, columnDefinition = "text")
    private String text;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "sla_hours_snapshot")
    private Integer slaHoursSnapshot;

    @Column(name = "expected_by")
    private Instant expectedBy;

    @Column(name = "sla_paused_at")
    private Instant slaPausedAt;

    @Column(name = "interpreted_at")
    private Instant interpretedAt;

    @Column(name = "cancelled_reason", columnDefinition = "text")
    private String cancelledReason;

    /** The SUBMIT ledger entry that paid for this dream. */
    @Column(name = "ledger_entry_id")
    private UUID ledgerEntryId;

    @Column(name = "assignee_id")
    private UUID assigneeId;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
