package com.saadat.auth.service;

import com.saadat.common.error.UnauthorizedException;
import com.saadat.common.web.LogMask;
import com.saadat.config.props.AppProperties;
import com.saadat.mail.FrontendPaths;
import com.saadat.mail.MailService;
import com.saadat.mail.MailTemplates;
import com.saadat.settings.SettingKeys;
import com.saadat.settings.SettingsService;
import com.saadat.users.domain.MagicLink;
import com.saadat.users.repo.MagicLinkRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Magic-link login (spec §3): a 32-byte url-safe token (for the link) and a 6-digit code (typed by hand).
 * Only hashes are stored (token: SHA-256, code: HMAC-SHA256 keyed with the server secret). TTL from setting
 * {@code auth.magic_ttl_minutes}; single use; the code path allows {@link #MAX_CODE_ATTEMPTS} wrong guesses
 * before the link is burned.
 */
@Slf4j
@Service
public class MagicLinkService {

    /** Wrong codes allowed per link before it is invalidated. */
    public static final int MAX_CODE_ATTEMPTS = 5;
    /** Links that may be requested for one e-mail within one TTL window (silently ignored above). */
    public static final int MAX_REQUESTS_PER_WINDOW = 5;

    public static final String CODE_INVALID = "MAGIC_LINK_INVALID";
    public static final String CODE_EXPIRED = "MAGIC_LINK_EXPIRED";
    public static final String CODE_TOO_MANY_ATTEMPTS = "MAGIC_LINK_TOO_MANY_ATTEMPTS";

    private final MagicLinkRepository repository;
    private final SettingsService settings;
    private final AppProperties properties;
    private final MailService mailService;
    private final EntityManager entityManager;
    private final Clock clock;

    public MagicLinkService(MagicLinkRepository repository, SettingsService settings, AppProperties properties,
                            MailService mailService, EntityManager entityManager, Clock clock) {
        this.repository = repository;
        this.settings = settings;
        this.properties = properties;
        this.mailService = mailService;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    public Duration ttl() {
        return Duration.ofMinutes(settings.getInt(SettingKeys.AUTH_MAGIC_TTL_MINUTES));
    }

    /**
     * Creates a link and e-mails it (asynchronously). Silently does nothing when the e-mail requested too many
     * links recently — the endpoint always answers 204 so it cannot be used to probe accounts.
     */
    public void request(String rawEmail, String ip, com.saadat.common.domain.Locale locale) {
        String email = normalizeEmail(rawEmail);
        Instant windowStart = clock.instant().minus(ttl());
        if (repository.countByEmailAndCreatedAtAfter(email, windowStart) >= MAX_REQUESTS_PER_WINDOW) {
            log.info("Magic link throttled for {}", LogMask.email(email));
            return;
        }
        IssuedMagicLink issued = issue(email, ip);
        Map<String, Object> model = new HashMap<>();
        model.put("link", linkFor(issued.token()));
        model.put("code", issued.code());
        model.put("ttlMinutes", ttl().toMinutes());
        mailService.sendAsync(null, email, MailTemplates.MAGIC_LINK, locale, model, null);
    }

    /** Persists a new link and returns the raw secrets (only ever sent by e-mail; exposed for tests). */
    @Transactional
    public IssuedMagicLink issue(String rawEmail, String ip) {
        String email = normalizeEmail(rawEmail);
        String token = SecureTokens.randomToken();
        String code = SecureTokens.randomCode();
        Instant now = clock.instant();
        MagicLink link = new MagicLink();
        link.setEmail(email);
        link.setTokenHash(SecureTokens.sha256(token));
        link.setCodeHash(codeHash(email, code));
        link.setCreatedAt(now);
        link.setExpiresAt(now.plus(ttl()));
        link.setCreatedIp(ip);
        link.setAttempts(0);
        repository.save(link);
        return new IssuedMagicLink(token, code, link.getExpiresAt());
    }

    /** Consumes a link by its token; returns the (lower-case) e-mail. */
    @Transactional(noRollbackFor = UnauthorizedException.class)
    public String verifyToken(String token) {
        if (token == null || token.isBlank()) {
            throw new UnauthorizedException("Invalid link", CODE_INVALID);
        }
        MagicLink link = repository.findByTokenHash(SecureTokens.sha256(token.trim()))
                .orElseThrow(() -> new UnauthorizedException("Invalid link", CODE_INVALID));
        lock(link);
        Instant now = clock.instant();
        checkUsable(link, now);
        link.setUsedAt(now);
        repository.save(link);
        return link.getEmail();
    }

    /** Consumes the latest unused link of {@code email} by its 6-digit code; returns the e-mail. */
    @Transactional(noRollbackFor = UnauthorizedException.class)
    public String verifyCode(String rawEmail, String code) {
        if (rawEmail == null || rawEmail.isBlank() || code == null || code.isBlank()) {
            throw new UnauthorizedException("Invalid code", CODE_INVALID);
        }
        String email = normalizeEmail(rawEmail);
        Optional<MagicLink> latest = repository.findTopByEmailAndUsedAtIsNullOrderByCreatedAtDesc(email);
        if (latest.isEmpty()) {
            throw new UnauthorizedException("Invalid code", CODE_INVALID);
        }
        MagicLink link = latest.get();
        lock(link);
        Instant now = clock.instant();
        checkUsable(link, now);
        if (link.getAttempts() >= MAX_CODE_ATTEMPTS) {
            link.setUsedAt(now);
            repository.save(link);
            throw new UnauthorizedException("Too many attempts", CODE_TOO_MANY_ATTEMPTS);
        }
        if (!SecureTokens.equalsConstantTime(link.getCodeHash(), codeHash(email, code.trim()))) {
            link.setAttempts(link.getAttempts() + 1);
            if (link.getAttempts() >= MAX_CODE_ATTEMPTS) {
                link.setUsedAt(now); // burned
            }
            repository.save(link);
            throw new UnauthorizedException("Invalid code", CODE_INVALID);
        }
        link.setUsedAt(now);
        repository.save(link);
        return link.getEmail();
    }

    /** {@code ${frontend}/auth/callback?token=…} */
    public String linkFor(String token) {
        return trimSlash(properties.getFrontendUrl()) + FrontendPaths.MAGIC_CALLBACK + "?"
                + FrontendPaths.MAGIC_CALLBACK_TOKEN_PARAM + "=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
    }

    public static String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    private void lock(MagicLink link) {
        // SELECT ... FOR UPDATE + reload: two concurrent verifications cannot both consume the link
        entityManager.refresh(link, LockModeType.PESSIMISTIC_WRITE);
    }

    private static void checkUsable(MagicLink link, Instant now) {
        if (link.isUsed()) {
            throw new UnauthorizedException("Link already used", CODE_INVALID);
        }
        if (link.isExpired(now)) {
            throw new UnauthorizedException("Link expired", CODE_EXPIRED);
        }
    }

    private String codeHash(String email, String code) {
        return SecureTokens.hmacSha256(properties.getAuth().getJwtSecret(), email + ":" + code);
    }

    private static String trimSlash(String url) {
        String u = url == null ? "" : url.trim();
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        return u;
    }

    /** Raw secrets of a freshly issued link. */
    public record IssuedMagicLink(String token, String code, Instant expiresAt) {
        @Override
        public String toString() {
            return "IssuedMagicLink[expiresAt=" + expiresAt + "]";
        }
    }
}
