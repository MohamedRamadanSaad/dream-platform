package com.saadat.auth.service;

import com.saadat.auth.api.AuthDtos.AuthResponse;
import com.saadat.auth.api.AuthDtos.MagicVerifyRequest;
import com.saadat.auth.api.AuthDtos.OnboardingRequest;
import com.saadat.auth.service.GoogleTokenVerifier.GoogleIdentity;
import com.saadat.auth.service.RefreshTokenService.IssuedRefreshToken;
import com.saadat.auth.service.RefreshTokenService.Rotation;
import com.saadat.common.domain.AuthProvider;
import com.saadat.common.domain.CountrySource;
import com.saadat.common.domain.Locale;
import com.saadat.common.domain.Role;
import com.saadat.common.error.ConflictException;
import com.saadat.common.error.UnauthorizedException;
import com.saadat.common.security.JwtService;
import com.saadat.common.web.CountryResolver.ResolvedCountry;
import com.saadat.common.web.LogMask;
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
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Login flows (Google, magic link), refresh, logout and onboarding. Every successful login/refresh:
 * upserts the user, links the identity, stores the country (first time only), records a user_sessions row,
 * updates last_login_at and grants INTERPRETER when the e-mail is in setting {@code interpreter.emails}.
 */
@Slf4j
@Service
public class AuthService {

    public static final String CODE_EMAIL_NOT_VERIFIED = "EMAIL_NOT_VERIFIED";
    public static final String CODE_ACCOUNT_UNAVAILABLE = "ACCOUNT_UNAVAILABLE";

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

    public AuthService(UserRepository userRepository, AuthIdentityRepository identityRepository,
                       UserSessionRepository sessionRepository, GoogleTokenVerifier googleTokenVerifier,
                       MagicLinkService magicLinkService, RefreshTokenService refreshTokenService,
                       JwtService jwtService, SettingsService settings, UserDtoMapper userDtoMapper, Clock clock) {
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
    }

    // ------------------------------------------------------------------ flows

    @Transactional
    public LoginResult loginWithGoogle(String idToken, LoginContext ctx) {
        GoogleIdentity identity = googleTokenVerifier.verify(idToken);
        if (!identity.emailVerified() || identity.email() == null || identity.email().isBlank()) {
            throw new UnauthorizedException("Google e-mail is not verified", CODE_EMAIL_NOT_VERIFIED);
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
        return complete(user, AuthProvider.GOOGLE, identity.subject(), ctx);
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
        return complete(user, AuthProvider.MAGIC_LINK, email, ctx);
    }

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
        return new LoginResult(response(user), rotation.next());
    }

    public void logout(String rawRefreshToken) {
        refreshTokenService.revoke(rawRefreshToken);
    }

    @Transactional
    public UserDto onboard(UUID userId, OnboardingRequest request) {
        User user = userRepository.findById(userId)
                .filter(u -> !u.isDeleted())
                .orElseThrow(() -> new UnauthorizedException("Account unavailable", CODE_ACCOUNT_UNAVAILABLE));
        user.setName(request.name().trim());
        user.setGender(request.gender());
        user.setOnboarded(true);
        userRepository.save(user);
        return userDtoMapper.toDto(user);
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

    private LoginResult complete(User user, AuthProvider provider, String subject, LoginContext ctx) {
        Instant now = clock.instant();
        linkIdentity(user, provider, subject, now);
        touch(user, ctx, now);
        IssuedRefreshToken refreshToken = refreshTokenService.issue(user.getId(), ctx.userAgent());
        return new LoginResult(response(user), refreshToken);
    }

    /** Country (first time), interpreter role, last_login_at, user_sessions row. */
    private void touch(User user, LoginContext ctx, Instant now) {
        ResolvedCountry resolved = ctx.country();
        if (user.getCountryCode() == null && resolved != null) {
            user.setCountryCode(resolved.countryCode());
            user.setCountrySource(resolved.source());
        }
        if (user.getRole() != Role.INTERPRETER && isInterpreterEmail(user.getEmail())) {
            log.info("Granting INTERPRETER role to {}", LogMask.email(user.getEmail()));
            user.setRole(Role.INTERPRETER);
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

    private boolean isInterpreterEmail(String email) {
        if (email == null) {
            return false;
        }
        for (String candidate : settings.getList(SettingKeys.INTERPRETER_EMAILS)) {
            if (candidate.equalsIgnoreCase(email)) {
                return true;
            }
        }
        return false;
    }

    private AuthResponse response(User user) {
        int ttlMinutes = settings.getInt(SettingKeys.AUTH_ACCESS_TTL_MINUTES);
        String token = jwtService.issue(user.getId(), user.getRole(), user.getEmail(), ttlMinutes);
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
