package com.saadat.auth.service;

import com.saadat.config.props.AppProperties;

import com.saadat.auth.api.AuthDtos;
import com.saadat.auth.api.AuthDtos.AuthResponse;
import com.saadat.auth.api.AuthDtos.MagicVerifyRequest;
import com.saadat.auth.api.AuthDtos.OnboardingRequest;
import com.saadat.auth.service.GoogleTokenVerifier.GoogleIdentity;
import com.saadat.auth.service.RefreshTokenService.IssuedRefreshToken;
import com.saadat.auth.service.RefreshTokenService.Rotation;
import com.saadat.common.domain.AuthProvider;
import com.saadat.common.domain.CountrySource;
import com.saadat.common.domain.Gender;
import com.saadat.common.domain.Locale;
import com.saadat.common.domain.Role;
import com.saadat.common.error.ConflictException;
import com.saadat.common.error.UnauthorizedException;
import com.saadat.common.security.JwtService;
import com.saadat.common.util.Ages;
import com.saadat.common.web.CountryResolver.ResolvedCountry;
import com.saadat.common.web.LogMask;
import com.saadat.mail.EventMailer;
import com.saadat.mail.FrontendPaths;
import com.saadat.mail.MailService;
import com.saadat.mail.MailTemplates;
import com.saadat.mail.MessageText;
import com.saadat.settings.SettingKeys;
import com.saadat.settings.SettingsService;
import com.saadat.users.api.UserDto;
import com.saadat.users.domain.AuthIdentity;
import com.saadat.users.domain.User;
import com.saadat.users.domain.UserSession;
import com.saadat.users.repo.AuthIdentityRepository;
import com.saadat.users.repo.UserRepository;
import com.saadat.users.repo.UserSessionRepository;
import com.saadat.users.service.UserDtoMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Login flows (Google, magic link), refresh, logout and onboarding. Every successful login/refresh:
 * upserts the user, links the identity, stores the country (first time only), records a user_sessions row,
 * updates last_login_at and sets the role from the interpreter rules (see {@link #expectedRole(String)}).
 *
 * <p>A login starts a refresh-token family (= a signed-in device) in the requested "remember me" mode and issues an
 * access token carrying that family as {@code sid}; a login from a browser + system the account did not use recently
 * sends the {@code new-sign-in} e-mail ({@link DeviceService}). A refresh keeps the family, its mode and its country.
 */
@Slf4j
@Service
public class AuthService {

    public static final String CODE_EMAIL_NOT_VERIFIED = "EMAIL_NOT_VERIFIED";
    public static final String CODE_ACCOUNT_UNAVAILABLE = "ACCOUNT_UNAVAILABLE";
    /** Interpreter-domain e-mails must use the magic link/code, never Google. */
    public static final String CODE_INTERPRETER_USE_MAGIC = "INTERPRETER_USE_MAGIC";

    private static final int USER_AGENT_MAX = 512;
    private static final int NAME_MAX = 200;

    private final UserRepository userRepository;
    private final AuthIdentityRepository identityRepository;
    private final UserSessionRepository sessionRepository;
    private final GoogleTokenVerifier googleTokenVerifier;
    private final MagicLinkService magicLinkService;
    private final RefreshTokenService refreshTokenService;
    private final JwtService jwtService;
    private final SettingsService settings;
    private final UserDtoMapper userDtoMapper;
    private final Clock clock;
    private final AppProperties appProperties;
    private final EventMailer eventMailer;
    private final MessageText messageText;
    private final DeviceService deviceService;

    public AuthService(UserRepository userRepository, AuthIdentityRepository identityRepository,
                       UserSessionRepository sessionRepository, GoogleTokenVerifier googleTokenVerifier,
                       MagicLinkService magicLinkService, RefreshTokenService refreshTokenService,
                       JwtService jwtService, SettingsService settings, UserDtoMapper userDtoMapper, Clock clock,
                       AppProperties appProperties, EventMailer eventMailer, MessageText messageText,
                       DeviceService deviceService) {
        this.userRepository = userRepository;
        this.identityRepository = identityRepository;
        this.sessionRepository = sessionRepository;
        this.googleTokenVerifier = googleTokenVerifier;
        this.magicLinkService = magicLinkService;
        this.refreshTokenService = refreshTokenService;
        this.jwtService = jwtService;
        this.settings = settings;
        this.userDtoMapper = userDtoMapper;
        this.clock = clock;
        this.appProperties = appProperties;
        this.eventMailer = eventMailer;
        this.messageText = messageText;
        this.deviceService = deviceService;
    }

    // ------------------------------------------------------------------ flows

    /** {@code rememberMe}: persistent cookie + auth.refresh_ttl_days, else session cookie + auth.session_ttl_hours. */
    @Transactional
    public LoginResult loginWithGoogle(String idToken, boolean rememberMe, LoginContext ctx) {
        GoogleIdentity identity = googleTokenVerifier.verify(idToken);
        if (!identity.emailVerified() || identity.email() == null || identity.email().isBlank()) {
            throw new UnauthorizedException("Google e-mail is not verified", CODE_EMAIL_NOT_VERIFIED);
        }
        if (isInterpreterDomain(identity.email())) {
            throw new UnauthorizedException("Interpreter accounts sign in with the e-mail code only",
                    CODE_INTERPRETER_USE_MAGIC);
        }
        User user = null;
        Optional<AuthIdentity> linked =
                identityRepository.findByProviderAndProviderSubject(AuthProvider.GOOGLE, identity.subject());
        if (linked.isPresent()) {
            user = userRepository.findById(linked.get().getUserId()).filter(u -> !u.isDeleted()).orElse(null);
        }
        if (user == null) {
            user = findOrCreate(identity.email(), identity.name(), ctx);
        }
        return complete(user, AuthProvider.GOOGLE, identity.subject(), rememberMe, ctx);
    }

    /** Always succeeds from the caller's point of view (the endpoint answers 204). */
    public void requestMagicLink(String email, LoginContext ctx) {
        String normalized = MagicLinkService.normalizeEmail(email);
        Locale locale = userRepository.findByEmailIgnoreCase(normalized)
                .filter(u -> !u.isDeleted())
                .map(User::getLocale)
                .orElse(ctx.requestLocale());
        magicLinkService.request(normalized, ctx.ip(), locale);
    }

    @Transactional(noRollbackFor = UnauthorizedException.class)
    public LoginResult verifyMagicLink(MagicVerifyRequest request, LoginContext ctx) {
        final String email;
        if (request.token() != null && !request.token().isBlank()) {
            email = magicLinkService.verifyToken(request.token());
        } else if (request.email() != null && request.code() != null) {
            email = magicLinkService.verifyCode(request.email(), request.code());
        } else {
            throw new UnauthorizedException("Provide a token or an e-mail and code", MagicLinkService.CODE_INVALID);
        }
        User user = findOrCreate(email, null, ctx);
        return complete(user, AuthProvider.MAGIC_LINK, email, AuthDtos.rememberMe(request.rememberMe()), ctx);
    }

    /** Rotates the refresh token: same family (device), same "remember me" mode, expiry slides forward. */
    @Transactional(noRollbackFor = UnauthorizedException.class)
    public LoginResult refresh(String rawRefreshToken, LoginContext ctx) {
        Rotation rotation = refreshTokenService.rotate(rawRefreshToken, ctx.userAgent());
        User user = userRepository.findById(rotation.userId()).orElse(null);
        if (user == null || user.isDeleted()) {
            refreshTokenService.revokeAll(rotation.userId());
            throw new UnauthorizedException("Account unavailable", CODE_ACCOUNT_UNAVAILABLE);
        }
        Instant now = clock.instant();
        touch(user, ctx, now);
        return new LoginResult(response(user, rotation.next().familyId()), rotation.next());
    }

    public void logout(String rawRefreshToken) {
        refreshTokenService.revoke(rawRefreshToken);
    }

    @Transactional
    public UserDto onboard(UUID userId, OnboardingRequest request) {
        User user = userRepository.findById(userId)
                .filter(u -> !u.isDeleted())
                .orElseThrow(() -> new UnauthorizedException("Account unavailable", CODE_ACCOUNT_UNAVAILABLE));
        boolean firstTime = !user.isOnboarded();
        user.setName(request.name().trim());
        user.setGender(request.gender());
        user.setBirthDate(request.birthDate());
        user.setOnboarded(true);
        userRepository.save(user);
        if (firstTime) {
            mailOnboarded(user);
        }
        return userDtoMapper.toDto(user);
    }

    /** {@code welcome} to the user and {@code new-user} (name, country, age) to the interpreter(s), after commit. */
    private void mailOnboarded(User user) {
        UUID id = user.getId();
        Map<String, Object> welcome = new LinkedHashMap<>();
        welcome.put(MailService.MODEL_LINK, FrontendPaths.NEW_DREAM);
        eventMailer.toUser(user, MailTemplates.WELCOME, welcome, MailTemplates.WELCOME + ":" + id);

        String userName = user.getName();
        String countryCode = user.getCountryCode();
        Integer age = Ages.of(user.getBirthDate(), clock);
        Gender gender = user.getGender();
        eventMailer.toInterpreters(MailTemplates.NEW_USER, MailTemplates.NEW_USER + ":" + id, locale -> {
            Map<String, Object> model = new LinkedHashMap<>();
            model.put("userName", userName);
            model.put("countryName", userDtoMapper.countryName(countryCode, locale));
            model.put("age", age);
            model.put("gender", gender == null ? null
                    : messageText.get("report.gender." + gender.name(), locale, Map.of()));
            model.put(MailService.MODEL_LINK, FrontendPaths.adminUser(id));
            return model;
        });
    }

    // ------------------------------------------------------------------ internals

    private User findOrCreate(String rawEmail, String name, LoginContext ctx) {
        String email = MagicLinkService.normalizeEmail(rawEmail);
        Optional<User> existing = userRepository.findByEmailIgnoreCase(email).filter(u -> !u.isDeleted());
        if (existing.isPresent()) {
            return existing.get();
        }
        User user = new User();
        user.setEmail(email);
        user.setName(name == null ? "" : truncate(name.trim(), NAME_MAX));
        user.setRole(Role.USER);
        user.setLocale(ctx.requestLocale() == null ? Locale.AR : ctx.requestLocale());
        user.setOnboarded(false);
        user.setMarketingOptIn(false);
        user.setCreatedAt(clock.instant());
        log.info("Creating user {}", LogMask.email(email));
        return userRepository.save(user);
    }

    private LoginResult complete(User user, AuthProvider provider, String subject, boolean rememberMe,
                                 LoginContext ctx) {
        Instant now = clock.instant();
        linkIdentity(user, provider, subject, now);
        String sessionCountry = touch(user, ctx, now);
        // decided before the new family exists, so the new sign-in never matches itself
        boolean newDevice = deviceService.isNewDevice(user.getId(), ctx.userAgent(), now);
        IssuedRefreshToken refreshToken =
                refreshTokenService.issue(user.getId(), ctx.userAgent(), rememberMe, sessionCountry);
        if (newDevice) {
            deviceService.mailNewSignIn(user, ctx.userAgent(), sessionCountry, now, refreshToken.familyId());
        }
        return new LoginResult(response(user, refreshToken.familyId()), refreshToken);
    }

    /**
     * Country (first time), interpreter role, last_login_at, user_sessions row. Returns the country of this session
     * (the detected one, or the user's stored country when only the configured default is known).
     */
    private String touch(User user, LoginContext ctx, Instant now) {
        ResolvedCountry resolved = ctx.country();
        if (user.getCountryCode() == null && resolved != null) {
            user.setCountryCode(resolved.countryCode());
            user.setCountrySource(resolved.source());
        }
        // The role follows the rules on every sign-in, both ways: an e-mail removed from the interpreter rules
        // loses the INTERPRETER role instead of keeping it forever.
        Role expected = expectedRole(user.getEmail());
        if (user.getRole() != expected) {
            log.warn("Role of {} changed {} -> {}", LogMask.email(user.getEmail()), user.getRole(), expected);
            user.setRole(expected);
        }
        user.setLastLoginAt(now);
        userRepository.save(user);

        UserSession session = new UserSession();
        session.setUserId(user.getId());
        session.setCountryCode(resolved == null || resolved.source() == CountrySource.DEFAULT
                ? user.getCountryCode() : resolved.countryCode());
        session.setUserAgent(truncate(ctx.userAgent(), USER_AGENT_MAX));
        session.setStartedAt(now);
        sessionRepository.save(session);
        return session.getCountryCode();
    }

    private void linkIdentity(User user, AuthProvider provider, String subject, Instant now) {
        Optional<AuthIdentity> existing = identityRepository.findByProviderAndProviderSubject(provider, subject);
        AuthIdentity identity;
        if (existing.isPresent()) {
            identity = existing.get();
            if (!identity.getUserId().equals(user.getId())) {
                boolean ownerGone = userRepository.findById(identity.getUserId()).map(User::isDeleted).orElse(true);
                if (!ownerGone) {
                    throw new ConflictException("This sign-in is linked to another account", "IDENTITY_LINKED");
                }
                identity.setUserId(user.getId());
            }
        } else {
            identity = new AuthIdentity();
            identity.setUserId(user.getId());
            identity.setProvider(provider);
            identity.setProviderSubject(subject);
            identity.setCreatedAt(now);
        }
        identity.setLastUsedAt(now);
        identityRepository.save(identity);
    }

    /** True when the e-mail is on the interpreter domain (setting interpreter.email_domain). */
    public boolean isInterpreterDomain(String email) {
        if (email == null) {
            return false;
        }
        String domain = settings.getString(SettingKeys.INTERPRETER_EMAIL_DOMAIN, "");
        if (domain == null || domain.isBlank()) {
            return false;
        }
        domain = domain.trim().toLowerCase();
        if (domain.startsWith("@")) {
            domain = domain.substring(1);
        }
        return email.trim().toLowerCase().endsWith("@" + domain);
    }

    /** INTERPRETER when the e-mail matches the interpreter rules (domain, setting list, bootstrap env), else USER. */
    public Role expectedRole(String email) {
        return isInterpreterEmail(email) ? Role.INTERPRETER : Role.USER;
    }

    /** Interpreter rules: e-mail on interpreter.email_domain, in setting interpreter.emails, or in INTERPRETER_EMAILS. */
    public boolean isInterpreterEmail(String email) {
        if (email == null) {
            return false;
        }
        if (isInterpreterDomain(email)) {
            return true;
        }
        for (String candidate : settings.getList(SettingKeys.INTERPRETER_EMAILS)) {
            if (candidate.equalsIgnoreCase(email)) {
                return true;
            }
        }
        String bootstrap = appProperties.getAuth().getBootstrapInterpreterEmails();
        if (bootstrap != null && !bootstrap.isBlank()) {
            for (String candidate : bootstrap.split(",")) {
                if (candidate.trim().equalsIgnoreCase(email)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The JSON body: an access token for the refresh-token family {@code sessionId} (claim {@code sid}). */
    private AuthResponse response(User user, UUID sessionId) {
        int ttlMinutes = settings.getInt(SettingKeys.AUTH_ACCESS_TTL_MINUTES);
        String token = jwtService.issue(user.getId(), user.getRole(), user.getEmail(), ttlMinutes, sessionId);
        return new AuthResponse(token, ttlMinutes * 60L, userDtoMapper.toDto(user));
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() > max ? value.substring(0, max) : value;
    }

    // ------------------------------------------------------------------ types

    /** Request facts a login needs (built by the controller). */
    public record LoginContext(String userAgent, String ip, ResolvedCountry country, Locale requestLocale) {
    }

    /** The JSON body plus the refresh token that goes into the cookie. */
    public record LoginResult(AuthResponse response, IssuedRefreshToken refreshToken) {
    }
}
