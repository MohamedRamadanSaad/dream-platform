package com.saadat.users.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

/** Row of {@code user_sessions}: one per login/refresh — the source of "visits" analytics. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "user_sessions")
public class UserSession {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Nullable to allow anonymous visits in the future. */
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "country_code", columnDefinition = "char(2)")
    private String countryCode;

    @Column(name = "user_agent", length = 512)
    private String userAgent;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @PrePersist
    void onCreate() {
        if (startedAt == null) {
            startedAt = Instant.now();
        }
    }
}
