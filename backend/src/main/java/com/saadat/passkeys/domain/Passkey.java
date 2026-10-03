package com.saadat.passkeys.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.SoftDelete;

/**
 * Row of {@code passkeys}: one WebAuthn credential (fingerprint / face / device PIN sign-in) of an account. The
 * private key never leaves the user's device; the server keeps the credential ID, the COSE public key and the
 * signature counter. Soft-deleted ({@code deleted = true}) when the user removes it and with the account
 * (AccountService); a deleted row is invisible to every query.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@SoftDelete(columnName = "deleted")
@Table(name = "passkeys")
public class Passkey {

    /** Separator of {@link #transports} in the column. */
    public static final String TRANSPORT_SEPARATOR = ",";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /** WebAuthn credential ID ({@code rawId}), unique. */
    @Column(name = "credential_id", nullable = false, updatable = false)
    private byte[] credentialId;

    /** Credential public key, COSE_Key (CBOR). */
    @Column(name = "public_key", nullable = false, updatable = false)
    private byte[] publicKey;

    /** Highest signature counter seen (0 for passkeys that do not count, e.g. synced ones). */
    @Column(name = "sign_count", nullable = false)
    private long signCount;

    /** Authenticator model; all zeros for most passkeys (attestation "none"). */
    @Column(name = "aaguid", updatable = false)
    private UUID aaguid;

    /** Transports reported at registration ("internal,hybrid"), sent back in excludeCredentials hints. */
    @Column(name = "transports", length = 200)
    private String transports;

    /** Shown in the profile; defaults to "&lt;browser&gt; · &lt;os&gt;" of the registering browser. */
    @Column(name = "label", nullable = false, length = 100)
    private String label;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    /** {@link #transports} as a list (empty when none were reported). */
    public List<String> transportList() {
        if (transports == null || transports.isBlank()) {
            return List.of();
        }
        return Arrays.stream(transports.split(TRANSPORT_SEPARATOR)).map(String::trim).filter(t -> !t.isEmpty())
                .toList();
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
