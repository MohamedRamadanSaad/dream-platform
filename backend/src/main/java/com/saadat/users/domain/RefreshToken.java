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
import org.hibernate.annotations.SoftDelete;

/**
 * Row of {@code refresh_tokens}. The raw token (32 random bytes) only lives in the {@code rt} cookie; the DB
 * keeps its SHA-256. Rotation keeps the {@code familyId} (one family = one signed-in device), the sign-in mode
 * ({@code persistent}) and the sign-in country; reuse of a revoked token revokes the whole family.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@SoftDelete(columnName = "deleted")
@Table(name = "refresh_tokens")
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "token_hash", nullable = false, length = 128)
    private String tokenHash;

    @Column(name = "family_id", nullable = false)
    private UUID familyId;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "user_agent", length = 512)
    private String userAgent;

    /**
     * "Remember me": true = persistent cookie and lifetime auth.refresh_ttl_days; false = browser-session cookie and
     * lifetime auth.session_ttl_hours. Copied on rotation.
     */
    @Column(name = "persistent", nullable = false)
    private boolean persistent = true;

    /** Country of the family's sign-in (copied on rotation); null when unknown. */
    @Column(name = "country_code", columnDefinition = "char(2)")
    private String countryCode;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public boolean isExpired(Instant now) {
        return !expiresAt.isAfter(now);
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
