package com.saadat.auth.service;

import com.saadat.common.domain.Locale;
import com.saadat.common.domain.Role;
import com.saadat.common.error.NotFoundException;
import com.saadat.mail.EventMailer;
import com.saadat.mail.FrontendPaths;
import com.saadat.mail.MailService;
import com.saadat.mail.MailTemplates;
import com.saadat.mail.MessageText;
import com.saadat.settings.SettingKeys;
import com.saadat.settings.SettingsService;
import com.saadat.tracking.service.UserAgents;
import com.saadat.users.api.MeDtos.DeviceDto;
import com.saadat.users.domain.RefreshToken;
import com.saadat.users.domain.User;
import com.saadat.users.repo.RefreshTokenRepository;
import com.saadat.users.repo.UserRepository;
import com.saadat.users.service.UserDtoMapper;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Signed-in devices (docs/SESSIONS_PROFILE_CONTRACT.md §2–3). A device is one refresh-token family: it starts at a
 * sign-in and lives on through rotations until it is signed out, reused or expires. Access tokens carry the family
 * as {@code sid}, so signing a device out also stops its access token on the next request (JwtAuthFilter).
 *
 * <p>Also decides whether a sign-in comes from a new device and sends the {@code new-sign-in} e-mail.
 */
@Slf4j
@Service
public class DeviceService {

    static final String DEVICE_LABEL_KEY = "mail.new-sign-in.device";
    static final String DEVICE_TYPE_KEY_PREFIX = "mail.device.";

    private final RefreshTokenRepository tokenRepository;
    private final UserRepository userRepository;
    private final UserDtoMapper userDtoMapper;
    private final EventMailer eventMailer;
    private final MessageText messageText;
    private final SettingsService settings;
    private final Clock clock;

    public DeviceService(RefreshTokenRepository tokenRepository, UserRepository userRepository,
                         UserDtoMapper userDtoMapper, EventMailer eventMailer, MessageText messageText,
                         SettingsService settings, Clock clock) {
        this.tokenRepository = tokenRepository;
        this.userRepository = userRepository;
        this.userDtoMapper = userDtoMapper;
        this.eventMailer = eventMailer;
        this.messageText = messageText;
        this.settings = settings;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ devices list

    /**
     * The user's active families (at least one non-revoked, non-expired token), most recently active first.
     * {@code currentFamilyId} = the {@code sid} of the calling access token (may be null); {@code requestLocale}
     * localizes the country names (null = the user's own locale).
     */
    @Transactional(readOnly = true)
    public List<DeviceDto> list(UUID userId, UUID currentFamilyId, Locale requestLocale) {
        Instant now = clock.instant();
        // the newest active token of each family (rotation leaves exactly one active token per family)
        Map<UUID, RefreshToken> active = new LinkedHashMap<>();
        for (RefreshToken token : tokenRepository.findActiveByUserId(userId, now)) {
            active.merge(token.getFamilyId(), token, (a, b) -> a.getCreatedAt().isAfter(b.getCreatedAt()) ? a : b);
        }
        if (active.isEmpty()) {
            return List.of();
        }
        Map<UUID, Instant[]> spans = new HashMap<>();
        for (Object[] row : tokenRepository.findFamilySpans(List.copyOf(active.keySet()))) {
            spans.put((UUID) row[0], new Instant[] {toInstant(row[1]), toInstant(row[2])});
        }
        Locale locale = requestLocale != null ? requestLocale
                : userRepository.findById(userId).map(User::getLocale).orElse(Locale.AR);
        return active.values().stream()
                .map(token -> toDto(token, spans.get(token.getFamilyId()), currentFamilyId, locale))
                .sorted(Comparator.comparing(DeviceDto::lastActiveAt).reversed())
                .toList();
    }

    /** Signs one of the user's devices out (revokes the family); 404 when the family is not the user's. */
    @Transactional
    public void signOut(UUID userId, UUID familyId) {
        if (familyId == null || !tokenRepository.existsByUserIdAndFamilyId(userId, familyId)) {
            throw NotFoundException.of("Device", familyId);
        }
        int revoked = tokenRepository.revokeFamily(familyId, clock.instant());
        log.info("Device {} of user {} signed out ({} active tokens revoked)", familyId, userId, revoked);
    }

    /**
     * Signs out every device of the user except {@code currentFamilyId}. Without a current family (an access token
     * issued before devices existed) every device is signed out.
     */
    @Transactional
    public void signOutOthers(UUID userId, UUID currentFamilyId) {
        Instant now = clock.instant();
        int revoked = currentFamilyId == null
                ? tokenRepository.revokeAllForUser(userId, now)
                : tokenRepository.revokeAllForUserExceptFamily(userId, currentFamilyId, now);
        log.info("Other devices of user {} signed out ({} active tokens revoked)", userId, revoked);
    }

    // ------------------------------------------------------------------ new sign-in alert

    /**
     * True when the user signed in before and the browser + system of {@code userAgent} matches none of the user's
     * sign-ins of the last {@code auth.known_device_days} days (revoked families count: they are known devices).
     * Must be called BEFORE the new family is issued, otherwise the new sign-in would match itself.
     */
    public boolean isNewDevice(UUID userId, String userAgent, Instant now) {
        if (!tokenRepository.existsByUserId(userId)) {
            return false; // the very first sign-in: there is no earlier session to compare with
        }
        String browser = UserAgents.browser(userAgent);
        String os = UserAgents.os(userAgent);
        Instant since = now.minus(Duration.ofDays(settings.getInt(SettingKeys.AUTH_KNOWN_DEVICE_DAYS)));
        for (String known : tokenRepository.findUserAgentsSince(userId, since)) {
            if (browser.equals(UserAgents.browser(known)) && os.equals(UserAgents.os(known))) {
                return false;
            }
        }
        return true;
    }

    /** The {@code new-sign-in} e-mail to the user, sent after commit (switch {@code mail.event.new-sign-in}). */
    public void mailNewSignIn(User user, String userAgent, String countryCode, Instant signedInAt, UUID familyId) {
        eventMailer.toUser(user, MailTemplates.NEW_SIGN_IN, newSignInModel(user, userAgent, countryCode, signedInAt),
                MailTemplates.NEW_SIGN_IN + ":" + familyId);
    }

    /** Model of the {@code new-sign-in} e-mail in the user's locale: device, type, country, time and the link. */
    Map<String, Object> newSignInModel(User user, String userAgent, String countryCode, Instant signedInAt) {
        Locale locale = user.getLocale();
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("device", messageText.get(DEVICE_LABEL_KEY, locale,
                Map.of("browser", UserAgents.browser(userAgent), "os", UserAgents.os(userAgent))));
        model.put("deviceType",
                messageText.get(DEVICE_TYPE_KEY_PREFIX + UserAgents.device(userAgent).name(), locale, Map.of()));
        model.put("countryName", userDtoMapper.countryName(countryCode, locale));
        model.put("signedInAt", signedInAt);
        model.put(MailService.MODEL_LINK, FrontendPaths.devices(user.getRole() == Role.INTERPRETER));
        return model;
    }

    // ------------------------------------------------------------------ internals

    private DeviceDto toDto(RefreshToken newest, Instant[] span, UUID currentFamilyId, Locale locale) {
        String userAgent = newest.getUserAgent();
        String code = newest.getCountryCode() == null || newest.getCountryCode().isBlank()
                ? null : newest.getCountryCode().trim();
        Instant signedInAt = span == null || span[0] == null ? newest.getCreatedAt() : span[0];
        Instant lastActiveAt = span == null || span[1] == null ? newest.getCreatedAt() : span[1];
        return new DeviceDto(
                newest.getFamilyId(),
                UserAgents.browser(userAgent),
                UserAgents.os(userAgent),
                UserAgents.device(userAgent),
                code,
                code == null ? null : userDtoMapper.countryName(code, locale),
                signedInAt,
                lastActiveAt,
                newest.getFamilyId().equals(currentFamilyId),
                newest.isPersistent());
    }

    /** Aggregates over timestamptz come back as Instant (Hibernate 6); older drivers may hand other types. */
    private static Instant toInstant(Object value) {
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof OffsetDateTime odt) {
            return odt.toInstant();
        }
        if (value instanceof Timestamp ts) {
            return ts.toInstant();
        }
        return null;
    }
}
