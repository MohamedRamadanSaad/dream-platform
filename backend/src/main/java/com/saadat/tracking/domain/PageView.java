package com.saadat.tracking.domain;

import com.saadat.common.domain.Device;
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

/** Row of {@code page_views}: one SPA route change. Visitors = distinct {@code sessionId}. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "page_views")
public class PageView {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "path", nullable = false, length = 255)
    private String path;

    @Column(name = "session_id", nullable = false, length = 100)
    private String sessionId;

    @Column(name = "visitor_id", length = 100)
    private String visitorId;

    /** The signed-in user when a valid Bearer token was sent, else null. */
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "country_code", columnDefinition = "char(2)")
    private String countryCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "device", nullable = false, length = 16)
    private Device device;

    /** Host of the referrer only (null = direct / own site). */
    @Column(name = "referrer_host", length = 255)
    private String referrerHost;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
