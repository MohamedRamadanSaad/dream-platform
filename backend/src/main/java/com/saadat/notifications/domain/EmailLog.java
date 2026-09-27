package com.saadat.notifications.domain;

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
 * Row of {@code email_log}. {@code ref} is a free correlation key (e.g. the dream id) used to send
 * one-off e-mails only once: {@code existsByTemplateAndRef("testimonial-request", dreamId)}.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "email_log")
public class EmailLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "to_email", nullable = false, length = 320)
    private String toEmail;

    @Column(name = "template", nullable = false, length = 64)
    private String template;

    @Column(name = "ref", length = 128)
    private String ref;

    @Column(name = "subject", length = 500)
    private String subject;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private EmailStatus status;

    @Column(name = "provider_id", length = 255)
    private String providerId;

    @Column(name = "error", columnDefinition = "text")
    private String error;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
