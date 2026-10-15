package com.saadat.auth.service;

import com.saadat.common.domain.CodePurpose;
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
 *
 * <p>Purposes (V37): {@link #request}/{@link #issue}/{@link #verifyToken}/{@link #verifyCode} are the SIGN_IN path
 * and only ever see SIGN_IN rows. {@link #requestCode}/{@link #issueCode} e-mail a code (no link) for SIGN_UP or
 * RESET_PASSWORD; {@link #verifyPurposeCode} checks it (same attempt rules) and turns it into a single-use ticket
 * (SHA-256 stored, {@link #TICKET_TTL}) that {@link #consumeTicket} exchanges once for the e-mail.
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
    /** Unknown, used, expired or wrong-purpose ticket (401). */
    public static final String CODE_TICKET_INVALID = "TICKET_INVALID";

    /** A verified sign-up / reset code gives a ticket valid this long. */
    public static final Duration TICKET_TTL = Duration.ofMinutes(15);


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
        if (throttled(email)) {
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

    /**
     * E-mails a 6-digit code (template {@code email-code}, no link) for {@code purpose} SIGN_UP or RESET_PASSWORD.
     * Throttled together with the magic links ({@link #MAX_REQUESTS_PER_WINDOW} per e-mail per TTL window, all
     * purposes counted), silently.
     */
    public void requestCode(String rawEmail, CodePurpose purpose, String ip, com.saadat.common.domain.Locale locale) {
        requireCodePurpose(purpose);
        String email = normalizeEmail(rawEmail);
        if (throttled(email)) {
            log.info("E-mail code ({}) throttled for {}", purpose, LogMask.email(email));
            return;
        }
        IssuedMagicLink issued = issueCode(email, purpose, ip);
        Map<String, Object> model = new HashMap<>();
        model.put("code", issued.code());
        model.put("ttlMinutes", ttl().toMinutes());
        model.put(MailService.MODEL_PURPOSE, purpose.name());
        mailService.sendAsync(null, email, MailTemplates.EMAIL_CODE, locale, model, null);
    }

    /** Persists a new SIGN_IN link and returns the raw secrets (only ever sent by e-mail; exposed for tests). */
    @Transactional
    public IssuedMagicLink issue(String rawEmail, String ip) {
        return issueFor(rawEmail, CodePurpose.SIGN_IN, ip);
    }

    /** Persists a new SIGN_UP / RESET_PASSWORD code and returns the raw secrets (exposed for tests). */
    @Transactional
    public IssuedMagicLink issueCode(String rawEmail, CodePurpose purpose, String ip) {
        requireCodePurpose(purpose);
        return issueFor(rawEmail, purpose, ip);
    }

    private IssuedMagicLink issueFor(String rawEmail, CodePurpose purpose, String ip) {
        String email = normalizeEmail(rawEmail);
        // a token exists for every row (column NOT NULL, unique); only SIGN_IN tokens are ever accepted
        String token = SecureTokens.randomToken();
        String code = SecureTokens.randomCode();
        Instant now = clock.instant();
        MagicLink link = new MagicLink();
        link.setPurpose(purpose);
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
                .filter(l -> l.getPurpose() == CodePurpose.SIGN_IN)
                .orElseThrow(() -> new UnauthorizedException("Invalid link", CODE_INVALID));
        lock(link);
        Instant now = clock.instant();
        checkUsable(link, now);
        link.setUsedAt(now);
        repository.save(link);
        return link.getEmail();
    }

    /** Consumes the latest unused SIGN_IN link of {@code email} by its 6-digit code; returns the e-mail. */
    @Transactional(noRollbackFor = UnauthorizedException.class)
    public String verifyCode(String rawEmail, String code) {
        return consumeCode(rawEmail, code, CodePurpose.SIGN_IN).getEmail();
    }

    /**
     * Verifies a SIGN_UP / RESET_PASSWORD code (same attempt rules as {@link #verifyCode}; a code of another purpose
     * never matches) and gives a single-use ticket bound to that e-mail + purpose, valid {@link #TICKET_TTL}.
     */
    @Transactional(noRollbackFor = UnauthorizedException.class)
    public IssuedTicket verifyPurposeCode(String rawEmail, String code, CodePurpose purpose) {
        if (purpose == null || purpose == CodePurpose.SIGN_IN) {
            throw new UnauthorizedException("Invalid code", CODE_INVALID);
        }
        MagicLink link = consumeCode(rawEmail, code, purpose);
        String ticket = SecureTokens.randomToken();
        Instant expiresAt = clock.instant().plus(TICKET_TTL);
        link.setTicketHash(SecureTokens.sha256(ticket));
        link.setTicketExpiresAt(expiresAt);
        link.setTicketUsedAt(null);
        repository.save(link);
        return new IssuedTicket(ticket, expiresAt, TICKET_TTL.toSeconds());
    }

    /**
     * Consumes a ticket of {@code purpose} once (row lock: two concurrent uses cannot both pass); returns the e-mail.
     * Unknown, used, expired or wrong-purpose → 401 TICKET_INVALID (committed: a used ticket stays used).
     */
    @Transactional(noRollbackFor = UnauthorizedException.class)
    public String consumeTicket(String rawTicket, CodePurpose purpose) {
        if (rawTicket == null || rawTicket.isBlank() || purpose == null) {
            throw new UnauthorizedException("Invalid ticket", CODE_TICKET_INVALID);
        }
        MagicLink link = repository.findByTicketHash(SecureTokens.sha256(rawTicket.trim()))
                .orElseThrow(() -> new UnauthorizedException("Invalid ticket", CODE_TICKET_INVALID));
        lock(link);
        Instant now = clock.instant();
        if (link.getPurpose() != purpose || link.getTicketUsedAt() != null || link.getTicketExpiresAt() == null
                || !link.getTicketExpiresAt().isAfter(now)) {
            throw new UnauthorizedException("Invalid ticket", CODE_TICKET_INVALID);
        }
        link.setTicketUsedAt(now);
        repository.save(link);
        return link.getEmail();
    }

    /** The latest unused link of {@code email} and {@code purpose}, consumed by its code (or 401). */
    private MagicLink consumeCode(String rawEmail, String code, CodePurpose purpose) {
        if (rawEmail == null || rawEmail.isBlank() || code == null || code.isBlank()) {
            throw new UnauthorizedException("Invalid code", CODE_INVALID);
        }
        String email = normalizeEmail(rawEmail);
        Optional<MagicLink> latest =
                repository.findTopByEmailAndPurposeAndUsedAtIsNullOrderByCreatedAtDesc(email, purpose);
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
        return link;
    }

    /** {@code ${frontend}/auth/callback?token=…} */
    public String linkFor(String token) {
        return trimSlash(properties.getFrontendUrl()) + FrontendPaths.MAGIC_CALLBACK + "?"
                + FrontendPaths.MAGIC_CALLBACK_TOKEN_PARAM + "=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
    }

    public static String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    /** True when {@code email} asked for {@link #MAX_REQUESTS_PER_WINDOW} links/codes (any purpose) in one TTL. */
    private boolean throttled(String email) {
        Instant windowStart = clock.instant().minus(ttl());
        return repository.countByEmailAndCreatedAtAfter(email, windowStart) >= MAX_REQUESTS_PER_WINDOW;
    }

    private static void requireCodePurpose(CodePurpose purpose) {
        if (purpose != CodePurpose.SIGN_UP && purpose != CodePurpose.RESET_PASSWORD) {
            throw new IllegalArgumentException("Codes without a link are for SIGN_UP or RESET_PASSWORD only");
        }
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

    /** Raw ticket of a verified sign-up / reset code ({@code expiresIn} in seconds). */
    public record IssuedTicket(String ticket, Instant expiresAt, long expiresIn) {
        @Override
        public String toString() {
            return "IssuedTicket[expiresAt=" + expiresAt + "]";
        }
    }

    /** Raw secrets of a freshly issued link. */
    public record IssuedMagicLink(String token, String code, Instant expiresAt) {
        @Override
        public String toString() {
            return "IssuedMagicLink[expiresAt=" + expiresAt + "]";
        }
    }
}
