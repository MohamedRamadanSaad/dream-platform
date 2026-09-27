package com.saadat.auth.service;

import com.saadat.common.error.UnauthorizedException;
import com.saadat.common.web.LogMask;
import com.saadat.settings.SettingKeys;
import com.saadat.settings.SettingsService;
import com.saadat.users.domain.RefreshToken;
import com.saadat.users.repo.RefreshTokenRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Opaque refresh tokens (32 random bytes, SHA-256 stored) with rotation and reuse detection:
 * <ul>
 *   <li>every successful {@link #rotate} revokes the presented token and issues a new one in the same family;</li>
 *   <li>presenting an already-revoked token means it leaked → the whole family is revoked (401).</li>
 * </ul>
 */
@Slf4j
@Service
public class RefreshTokenService {

    public static final String CODE_INVALID = "REFRESH_INVALID";
    public static final String CODE_REUSED = "REFRESH_REUSED";

    private static final int USER_AGENT_MAX = 512;

    private final RefreshTokenRepository repository;
    private final SettingsService settings;
    private final EntityManager entityManager;
    private final Clock clock;

    public RefreshTokenService(RefreshTokenRepository repository, SettingsService settings,
                               EntityManager entityManager, Clock clock) {
        this.repository = repository;
        this.settings = settings;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    /** Lifetime of a refresh token (setting auth.refresh_ttl_days). */
    public Duration ttl() {
        return Duration.ofDays(settings.getInt(SettingKeys.AUTH_REFRESH_TTL_DAYS));
    }

    /** Starts a new family (a fresh login). */
    @Transactional
    public IssuedRefreshToken issue(UUID userId, String userAgent) {
        return issueInFamily(userId, UUID.randomUUID(), userAgent);
    }

    /**
     * Validates and rotates {@code rawToken}. Throws 401 when unknown, expired or revoked; a revoked token
     * additionally revokes its whole family (committed despite the exception).
     */
    @Transactional(noRollbackFor = UnauthorizedException.class)
    public Rotation rotate(String rawToken, String userAgent) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new UnauthorizedException("Missing refresh token", CODE_INVALID);
        }
        Optional<RefreshToken> found = repository.findByTokenHash(SecureTokens.sha256(rawToken));
        if (found.isEmpty()) {
            throw new UnauthorizedException("Invalid refresh token", CODE_INVALID);
        }
        // serialize concurrent refreshes of the same token (SELECT ... FOR UPDATE + reload)
        RefreshToken token = found.get();
        entityManager.refresh(token, LockModeType.PESSIMISTIC_WRITE);
        Instant now = clock.instant();
        if (token.isRevoked()) {
            int revoked = repository.revokeFamily(token.getFamilyId(), now);
            log.warn("Refresh token reuse detected (token {}), family {} revoked ({} active tokens)",
                    LogMask.token(rawToken), token.getFamilyId(), revoked);
            throw new UnauthorizedException("Refresh token reuse detected", CODE_REUSED);
        }
        if (token.isExpired(now)) {
            throw new UnauthorizedException("Refresh token expired", CODE_INVALID);
        }
        token.setRevokedAt(now);
        repository.save(token);
        IssuedRefreshToken next = issueInFamily(token.getUserId(), token.getFamilyId(), userAgent);
        return new Rotation(token.getUserId(), next);
    }

    /** Logout: revokes the presented token's family. Unknown tokens are ignored. */
    @Transactional
    public void revoke(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return;
        }
        repository.findByTokenHash(SecureTokens.sha256(rawToken))
                .ifPresent(t -> repository.revokeFamily(t.getFamilyId(), clock.instant()));
    }

    /** Account deletion / logout everywhere. */
    @Transactional
    public void revokeAll(UUID userId) {
        repository.revokeAllForUser(userId, clock.instant());
    }

    private IssuedRefreshToken issueInFamily(UUID userId, UUID familyId, String userAgent) {
        String raw = SecureTokens.randomToken();
        Duration ttl = ttl();
        Instant now = clock.instant();
        RefreshToken token = new RefreshToken();
        token.setUserId(userId);
        token.setFamilyId(familyId);
        token.setTokenHash(SecureTokens.sha256(raw));
        token.setCreatedAt(now);
        token.setExpiresAt(now.plus(ttl));
        token.setUserAgent(truncate(userAgent));
        repository.save(token);
        return new IssuedRefreshToken(raw, familyId, ttl);
    }

    private static String truncate(String userAgent) {
        if (userAgent == null) {
            return null;
        }
        return userAgent.length() > USER_AGENT_MAX ? userAgent.substring(0, USER_AGENT_MAX) : userAgent;
    }

    /** The raw token (goes into the cookie only), its family and lifetime. */
    public record IssuedRefreshToken(String rawToken, UUID familyId, Duration ttl) {
        @Override
        public String toString() {
            return "IssuedRefreshToken[familyId=" + familyId + "]";
        }
    }

    public record Rotation(UUID userId, IssuedRefreshToken next) {
    }
}
