package com.saadat.auth.service;

import com.saadat.auth.api.AuthDtos;
import com.saadat.auth.api.AuthDtos.CodeVerifyRequest;
import com.saadat.auth.api.AuthDtos.PasswordLoginRequest;
import com.saadat.auth.api.AuthDtos.PasswordResetRequest;
import com.saadat.auth.api.AuthDtos.SignupCompleteRequest;
import com.saadat.auth.api.AuthDtos.TicketResponse;
import com.saadat.auth.service.AuthService.LoginContext;
import com.saadat.auth.service.AuthService.LoginResult;
import com.saadat.auth.service.MagicLinkService.IssuedTicket;
import com.saadat.common.domain.AuthProvider;
import com.saadat.common.domain.CodePurpose;
import com.saadat.common.domain.Locale;
import com.saadat.common.error.ConflictException;
import com.saadat.common.error.TooManyRequestsException;
import com.saadat.common.error.UnauthorizedException;
import com.saadat.common.error.ValidationException;
import com.saadat.common.web.LogMask;
import com.saadat.users.domain.User;
import com.saadat.users.repo.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * E-mail + password accounts (owner's decision of 2026-10-15, V37).
 *
 * <ul>
 *   <li>Sign-up: {@link #requestSignup} e-mails a SIGN_UP code → {@link #verifyCode} gives a ticket →
 *       {@link #completeSignup} sets the password (and terms) on the account of that e-mail (created when missing,
 *       same defaults as a magic-link sign-up) and signs in.</li>
 *   <li>Forgot password: {@link #forgot} e-mails a RESET_PASSWORD code → {@link #verifyCode} → {@link #reset} sets
 *       the new password, clears the lockout, signs every other device out and signs this one in.</li>
 *   <li>{@link #login}: e-mail + password; {@link #MAX_FAILED_ATTEMPTS} wrong passwords in a row lock password
 *       sign-in for {@link #LOCK_DURATION} (429 TOO_MANY_ATTEMPTS with {@code retryAfterSeconds}).</li>
 * </ul>
 *
 * <p>Every sign-in goes through {@link AuthService#completeLogin} (identity PASSWORD = the e-mail, country, role sync,
 * user_sessions, new device family, new-sign-in e-mail). Hash: BCrypt strength {@link #BCRYPT_STRENGTH} of the
 * SHA-256 (hex) of the password, so every allowed password (up to 128 characters, any script) fits BCrypt's 72-byte
 * input limit without being cut. Passwords, codes and tickets are never logged.
 */
@Slf4j
@Service
public class PasswordService {

    public static final String CODE_ACCOUNT_EXISTS = "ACCOUNT_EXISTS";
    public static final String CODE_WEAK_PASSWORD = "WEAK_PASSWORD";
    public static final String CODE_INVALID_CREDENTIALS = "INVALID_CREDENTIALS";
    public static final String CODE_PASSWORD_NOT_SET = "PASSWORD_NOT_SET";
    public static final String CODE_TOO_MANY_ATTEMPTS = "TOO_MANY_ATTEMPTS";

    /** Password rule (the frontend mirrors it): 8 to 128 characters, at least one letter and one digit. */
    public static final int MIN_LENGTH = 8;
    public static final int MAX_LENGTH = 128;
    /** Wrong passwords in a row before password sign-in is locked. */
    public static final int MAX_FAILED_ATTEMPTS = 5;
    public static final Duration LOCK_DURATION = Duration.ofMinutes(15);
    static final int BCRYPT_STRENGTH = 12;

    private final AuthService authService;
    private final MagicLinkService magicLinkService;
    private final RefreshTokenService refreshTokenService;
    private final UserRepository userRepository;
    private final EntityManager entityManager;
    private final Clock clock;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(BCRYPT_STRENGTH);
    /** Compared against when the e-mail is unknown, so both answers take about as long. */
    private final String dummyHash;

    public PasswordService(AuthService authService, MagicLinkService magicLinkService,
                           RefreshTokenService refreshTokenService, UserRepository userRepository,
                           EntityManager entityManager, Clock clock) {
        this.authService = authService;
        this.magicLinkService = magicLinkService;
        this.refreshTokenService = refreshTokenService;
        this.userRepository = userRepository;
        this.entityManager = entityManager;
        this.clock = clock;
        this.dummyHash = encoder.encode(prehash(SecureTokens.randomToken()));
    }

    // ------------------------------------------------------------------ codes

    /** 204, or 409 ACCOUNT_EXISTS when the active account of this e-mail already has a password. */
    public void requestSignup(String rawEmail, LoginContext ctx) {
        String email = MagicLinkService.normalizeEmail(rawEmail);
        Optional<User> existing = activeUser(email);
        if (existing.isPresent() && existing.get().hasPassword()) {
            throw new ConflictException("An account with this e-mail already exists", CODE_ACCOUNT_EXISTS);
        }
        Locale locale = existing.map(User::getLocale).orElse(ctx.requestLocale());
        magicLinkService.requestCode(email, CodePurpose.SIGN_UP, ctx.ip(), locale);
    }

    /** Always succeeds (204): a RESET_PASSWORD code is e-mailed only when an active account exists. */
    public void forgot(String rawEmail, LoginContext ctx) {
        String email = MagicLinkService.normalizeEmail(rawEmail);
        Optional<User> user = activeUser(email);
        if (user.isEmpty()) {
            log.info("Password reset asked for an unknown e-mail {}", LogMask.email(email));
            return;
        }
        magicLinkService.requestCode(email, CodePurpose.RESET_PASSWORD, ctx.ip(), user.get().getLocale());
    }

    /** A SIGN_UP / RESET_PASSWORD code → its single-use ticket (401 MAGIC_LINK_* like the magic code). */
    public TicketResponse verifyCode(CodeVerifyRequest request) {
        IssuedTicket issued = magicLinkService.verifyPurposeCode(request.email(), request.code(), request.purpose());
        return new TicketResponse(issued.ticket(), issued.expiresIn());
    }

    // ------------------------------------------------------------------ sign-up, reset, login

    /**
     * Sets the password (and the terms acceptance) on the account of the ticket's e-mail, creating the account when
     * missing, then signs in. 422 WEAK_PASSWORD (the ticket stays usable), 401 TICKET_INVALID, 409 ACCOUNT_EXISTS when
     * the account got a password meanwhile.
     */
    @Transactional
    public LoginResult completeSignup(SignupCompleteRequest request, LoginContext ctx) {
        checkPolicy(request.password());
        String email = magicLinkService.consumeTicket(request.ticket(), CodePurpose.SIGN_UP);
        Optional<User> existing = activeUser(email);
        User user;
        if (existing.isPresent()) {
            user = existing.get();
            entityManager.refresh(user, LockModeType.PESSIMISTIC_WRITE); // a parallel sign-up waits here
            if (user.hasPassword()) {
                throw new ConflictException("An account with this e-mail already exists", CODE_ACCOUNT_EXISTS);
            }
        } else {
            user = authService.findOrCreateUser(email, null, ctx);
        }
        Instant now = clock.instant();
        setPassword(user, request.password(), now);
        if (user.getTermsAcceptedAt() == null) {
            user.setTermsAcceptedAt(now);
        }
        userRepository.save(user);
        log.info("Password set at sign-up for {}", LogMask.email(email));
        return authService.completeLogin(user, AuthProvider.PASSWORD, user.getEmail(),
                AuthDtos.rememberMe(request.rememberMe()), ctx);
    }

    /**
     * New password for the ticket's account: lockout cleared, every other device signed out (before this sign-in
     * starts its own family, which therefore stays), then signed in. 422 WEAK_PASSWORD, 401 TICKET_INVALID.
     */
    @Transactional
    public LoginResult reset(PasswordResetRequest request, LoginContext ctx) {
        checkPolicy(request.password());
        String email = magicLinkService.consumeTicket(request.ticket(), CodePurpose.RESET_PASSWORD);
        User user = activeUser(email)
                .orElseThrow(() -> new UnauthorizedException("Invalid ticket", MagicLinkService.CODE_TICKET_INVALID));
        entityManager.refresh(user, LockModeType.PESSIMISTIC_WRITE);
        setPassword(user, request.password(), clock.instant());
        userRepository.save(user);
        refreshTokenService.revokeAll(user.getId());
        log.info("Password reset for {}", LogMask.email(email));
        return authService.completeLogin(user, AuthProvider.PASSWORD, user.getEmail(),
                AuthDtos.rememberMe(request.rememberMe()), ctx);
    }

    /**
     * 401 INVALID_CREDENTIALS (unknown e-mail or wrong password, same answer), 409 PASSWORD_NOT_SET, 429
     * TOO_MANY_ATTEMPTS while locked; the wrong attempt that reaches {@link #MAX_FAILED_ATTEMPTS} locks and already
     * answers 429. The counter is committed despite the error.
     */
    @Transactional(noRollbackFor = {UnauthorizedException.class, TooManyRequestsException.class})
    public LoginResult login(PasswordLoginRequest request, LoginContext ctx) {
        String email = MagicLinkService.normalizeEmail(request.email());
        String password = request.password() == null ? "" : request.password();
        Optional<User> found = activeUser(email);
        if (found.isEmpty()) {
            encoder.matches(prehash(password), dummyHash);
            throw invalidCredentials();
        }
        User user = found.get();
        entityManager.refresh(user, LockModeType.PESSIMISTIC_WRITE); // serializes the wrong-password counter
        Instant now = clock.instant();
        if (user.getPasswordLockedUntil() != null && user.getPasswordLockedUntil().isAfter(now)) {
            throw locked(user.getPasswordLockedUntil(), now);
        }
        if (!user.hasPassword()) {
            encoder.matches(prehash(password), dummyHash);
            throw new ConflictException("This account has no password yet", CODE_PASSWORD_NOT_SET);
        }
        if (!encoder.matches(prehash(password), user.getPasswordHash())) {
            int failed = user.getFailedPasswordAttempts() + 1;
            if (failed >= MAX_FAILED_ATTEMPTS) {
                Instant until = now.plus(LOCK_DURATION);
                user.setFailedPasswordAttempts(0);
                user.setPasswordLockedUntil(until);
                userRepository.save(user);
                log.warn("Password sign-in locked for {} until {}", LogMask.email(email), until);
                throw locked(until, now);
            }
            user.setFailedPasswordAttempts(failed);
            userRepository.save(user);
            throw invalidCredentials();
        }
        user.setFailedPasswordAttempts(0);
        user.setPasswordLockedUntil(null);
        userRepository.save(user);
        return authService.completeLogin(user, AuthProvider.PASSWORD, user.getEmail(),
                AuthDtos.rememberMe(request.rememberMe()), ctx);
    }

    // ------------------------------------------------------------------ rule + hashing

    /** 422 WEAK_PASSWORD unless 8..128 characters with at least one letter and one digit (any script). */
    public static void checkPolicy(String password) {
        if (!isStrongEnough(password)) {
            throw new ValidationException("The password must be 8 to 128 characters with at least one letter and "
                    + "one digit", CODE_WEAK_PASSWORD);
        }
    }

    public static boolean isStrongEnough(String password) {
        if (password == null) {
            return false;
        }
        int length = password.codePointCount(0, password.length());
        if (length < MIN_LENGTH || length > MAX_LENGTH) {
            return false;
        }
        boolean letter = password.codePoints().anyMatch(Character::isLetter);
        boolean digit = password.codePoints().anyMatch(Character::isDigit);
        return letter && digit;
    }

    /** True when {@code rawPassword} is the user's password (no counters touched). */
    public boolean matches(User user, String rawPassword) {
        return user.hasPassword() && rawPassword != null
                && encoder.matches(prehash(rawPassword), user.getPasswordHash());
    }

    private void setPassword(User user, String rawPassword, Instant now) {
        user.setPasswordHash(encoder.encode(prehash(rawPassword)));
        user.setPasswordSetAt(now);
        user.setFailedPasswordAttempts(0);
        user.setPasswordLockedUntil(null);
    }

    /** 64 hex characters: always within BCrypt's 72-byte input, whatever the length or script of the password. */
    private static String prehash(String rawPassword) {
        return SecureTokens.sha256(rawPassword);
    }

    private Optional<User> activeUser(String email) {
        return userRepository.findByEmailIgnoreCase(email).filter(u -> !u.isDeleted());
    }

    private static UnauthorizedException invalidCredentials() {
        return new UnauthorizedException("Wrong e-mail or password", CODE_INVALID_CREDENTIALS);
    }

    private static TooManyRequestsException locked(Instant until, Instant now) {
        long seconds = Math.max(1L, (Duration.between(now, until).toMillis() + 999L) / 1000L);
        return new TooManyRequestsException("Too many wrong passwords; try again later", CODE_TOO_MANY_ATTEMPTS,
                seconds);
    }
}
