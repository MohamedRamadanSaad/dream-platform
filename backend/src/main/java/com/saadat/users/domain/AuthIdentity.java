package com.saadat.users.domain;

import com.saadat.common.domain.AuthProvider;
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
import org.hibernate.annotations.SoftDelete;

/**
 * Row of {@code auth_identities}: one account, many providers; unique (provider, provider_subject) among non-deleted
 * rows, so a deleted account's identity never blocks a new sign-up.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@SoftDelete(columnName = "deleted")
@Table(name = "auth_identities")
public class AuthIdentity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 16)
    private AuthProvider provider;

    /** Google "sub" for GOOGLE, the lower-case e-mail for MAGIC_LINK. */
    @Column(name = "provider_subject", nullable = false, length = 255)
    private String providerSubject;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
