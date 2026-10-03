package com.saadat.support.domain;

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
 * Row of {@code support_tickets}: one e-mail a person sent to the support mailbox. The message body is never stored,
 * only the sender, the subject and when it was received. {@code number} is the human ticket number (#1001…, from the
 * sequence {@code support_ticket_number_seq}); {@code messageId} (unique) de-duplicates repeated webhook deliveries
 * and threads our replies (In-Reply-To / References).
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "support_tickets")
public class SupportTicket {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "ticket_number", nullable = false, updatable = false)
    private long number;

    @Column(name = "from_email", nullable = false, length = 320)
    private String fromEmail;

    @Column(name = "from_name", length = 200)
    private String fromName;

    @Column(name = "subject", length = 500)
    private String subject;

    @Column(name = "message_id", length = 500, updatable = false)
    private String messageId;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private SupportTicketStatus status = SupportTicketStatus.NEW;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    /** The interpreter who last moved the ticket (null while NEW or after her account was deleted). */
    @Column(name = "last_action_by")
    private UUID lastActionBy;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = createdAt;
        }
        if (receivedAt == null) {
            receivedAt = createdAt;
        }
        if (status == null) {
            status = SupportTicketStatus.NEW;
        }
    }
}
