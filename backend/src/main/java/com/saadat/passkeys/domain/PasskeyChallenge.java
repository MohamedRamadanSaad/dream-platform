package com.saadat.passkeys.domain;

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
 * Row of {@code passkey_challenges}: the random challenge of one registration or sign-in ceremony, identified by the
 * {@code requestId} returned with the options. Single use ({@code used_at}) and short-lived
 * (setting {@code auth.passkey_challenge_ttl_seconds}); a registration challenge belongs to the signed-in user.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "passkey_challenges")
public class PasskeyChallenge {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "request_id")
    private UUID requestId;

    /** The account adding a passkey (REGISTRATION); null for a sign-in. */
    @Column(name = "user_id", updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", nullable = false, length = 16, updatable = false)
    private PasskeyPurpose purpose;

    @Column(name = "challenge", nullable = false, updatable = false)
    private byte[] challenge;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
