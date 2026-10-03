package com.saadat.passkeys.service;

import com.saadat.passkeys.domain.PasskeyChallenge;
import com.saadat.passkeys.domain.PasskeyPurpose;
import com.saadat.passkeys.repo.PasskeyChallengeRepository;
import com.saadat.settings.SettingKeys;
import com.saadat.settings.SettingsService;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Server-side store of WebAuthn challenges: 32 random bytes per ceremony, identified by the options' {@code requestId},
 * valid for setting {@code auth.passkey_challenge_ttl_seconds} and usable once (consumed even when the verification
 * that follows fails).
 */
@Service
public class PasskeyChallenges {

    /** Random bytes per challenge (WebAuthn asks for at least 16). */
    static final int CHALLENGE_BYTES = 32;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final PasskeyChallengeRepository repository;
    private final SettingsService settings;
    private final Clock clock;

    public PasskeyChallenges(PasskeyChallengeRepository repository, SettingsService settings, Clock clock) {
        this.repository = repository;
        this.settings = settings;
        this.clock = clock;
    }

    /** Lifetime of a challenge (also the {@code timeout} of the options). */
    public Duration ttl() {
        return Duration.ofSeconds(settings.getInt(SettingKeys.AUTH_PASSKEY_CHALLENGE_TTL_SECONDS));
    }

    /** A fresh challenge for {@code purpose}; {@code userId} = the account adding a passkey (null for a sign-in). */
    @Transactional
    public PasskeyChallenge issue(PasskeyPurpose purpose, UUID userId) {
        byte[] challenge = new byte[CHALLENGE_BYTES];
        RANDOM.nextBytes(challenge);
        Instant now = clock.instant();
        PasskeyChallenge row = new PasskeyChallenge();
        row.setPurpose(purpose);
        row.setUserId(userId);
        row.setChallenge(challenge);
        row.setCreatedAt(now);
        row.setExpiresAt(now.plus(ttl()));
        return repository.save(row);
    }

    /**
     * Consumes the sign-in challenge of {@code requestId}: returns it when it exists, is an AUTHENTICATION challenge,
     * was never used and has not expired; empty otherwise (also for a malformed id). Join the caller's transaction
     * and commit it even when the verification that follows fails, so the challenge stays used.
     */
    @Transactional
    public Optional<PasskeyChallenge> consumeSignIn(String requestId) {
        UUID id = parse(requestId);
        if (id == null || repository.consume(id, PasskeyPurpose.AUTHENTICATION, clock.instant()) != 1) {
            return Optional.empty();
        }
        return repository.findById(id);
    }

    /** Like {@link #consumeSignIn} for a REGISTRATION challenge issued to {@code userId} (anyone else's is left alone). */
    @Transactional
    public Optional<PasskeyChallenge> consumeRegistration(String requestId, UUID userId) {
        UUID id = parse(requestId);
        if (id == null || userId == null
                || repository.consumeOwn(id, PasskeyPurpose.REGISTRATION, userId, clock.instant()) != 1) {
            return Optional.empty();
        }
        return repository.findById(id);
    }

    /** Housekeeping: flags expired challenges deleted (soft delete); returns how many were flagged now. */
    @Transactional
    public int purgeExpired() {
        return repository.deleteExpiredBefore(clock.instant());
    }

    private static UUID parse(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(requestId.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
