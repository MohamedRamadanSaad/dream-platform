package com.saadat.support.domain;

import com.saadat.common.domain.EmailStatus;
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
 * Row of {@code support_ticket_events}: one message of the interpreter on a ticket (moved to IN_PROGRESS or CLOSED),
 * e-mailed to the sender; {@code emailStatus} is the outcome of that e-mail (null until it was attempted).
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "support_ticket_events")
public class SupportTicketEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "ticket_id", nullable = false, updatable = false)
    private UUID ticketId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 20, updatable = false)
    private SupportTicketAction action;

    @Column(name = "message", nullable = false, columnDefinition = "text", updatable = false)
    private String message;

    @Column(name = "actor_id")
    private UUID actorId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "email_status", length = 20)
    private EmailStatus emailStatus;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
