package com.saadat.passkeys.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.saadat.auth.api.AuthDtos;
import com.saadat.auth.service.AuthService;
import com.saadat.auth.service.AuthService.LoginContext;
import com.saadat.auth.service.AuthService.LoginResult;
import com.saadat.common.domain.Locale;
import com.saadat.common.domain.Role;
import com.saadat.common.error.ApiException;
import com.saadat.common.error.NotFoundException;
import com.saadat.common.error.UnauthorizedException;
import com.saadat.common.error.ValidationException;
import com.saadat.mail.EventMailer;
import com.saadat.mail.FrontendPaths;
import com.saadat.mail.MailService;
import com.saadat.mail.MailTemplates;
import com.saadat.mail.MessageText;
import com.saadat.passkeys.api.PasskeyDtos;
import com.saadat.passkeys.api.PasskeyDtos.AuthenticatorSelection;
import com.saadat.passkeys.api.PasskeyDtos.CreationOptions;
import com.saadat.passkeys.api.PasskeyDtos.CredentialDescriptor;
import com.saadat.passkeys.api.PasskeyDtos.CredentialParameters;
import com.saadat.passkeys.api.PasskeyDtos.PasskeyDto;
import com.saadat.passkeys.api.PasskeyDtos.PasskeyRegistrationRequest;
import com.saadat.passkeys.api.PasskeyDtos.PasskeySignInRequest;
import com.saadat.passkeys.api.PasskeyDtos.RegistrationOptionsResponse;
import com.saadat.passkeys.api.PasskeyDtos.RelyingParty;
import com.saadat.passkeys.api.PasskeyDtos.RequestOptions;
import com.saadat.passkeys.api.PasskeyDtos.SignInOptionsResponse;
import com.saadat.passkeys.api.PasskeyDtos.UserEntity;
import com.saadat.passkeys.domain.Passkey;
import com.saadat.passkeys.domain.PasskeyChallenge;
import com.saadat.passkeys.domain.PasskeyPurpose;
import com.saadat.passkeys.repo.PasskeyRepository;
import com.saadat.passkeys.service.WebAuthnRelyingParty.Assertion;
import com.saadat.passkeys.service.WebAuthnRelyingParty.NewCredential;
import com.saadat.passkeys.service.WebAuthnRelyingParty.StoredCredential;
import com.saadat.settings.SettingKeys;
import com.saadat.settings.SettingsService;
import com.saadat.tracking.service.UserAgents;
import com.saadat.users.domain.User;
import com.saadat.users.repo.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Passkeys (docs/PASSKEYS_CONTRACT.md): sign-in with the fingerprint, face or PIN of the user's device (WebAuthn,
 * discoverable credentials, user verification required), for USER and INTERPRETER accounts.
 * <ul>
 *   <li>registration (signed in): options with a single-use challenge and the caller's passkeys as
 *       {@code excludeCredentials} → verification → stored passkey + the {@code passkey-added} e-mail after commit;</li>
 *   <li>the caller's list / delete (404 for someone else's);</li>
 *   <li>sign-in (anonymous): options with an empty {@code allowCredentials} → the passkey is found by its credential
 *       ID, its owner must match the user handle, the assertion is verified, the counter and last use are updated,
 *       and the sign-in completes exactly like a magic-link one ({@link AuthService#loginWithPasskey}).</li>
 * </ul>
 * Any failed check answers {@link #CODE_INVALID} without saying which check failed (401 on sign-in, 422 on
 * registration so the signed-in caller's session is not mistaken for expired); the reason is logged. The challenge
 * is consumed even when the verification fails (the transactions commit on these errors).
 */
@Slf4j
@Service
public class PasskeyService {

    /** Problem code of every failed passkey verification. */
    public static final String CODE_INVALID = "PASSKEY_INVALID";
    /** Messages key of the default label: "{browser} · {os}" (same User-Agent parser as the devices list). */
    static final String DEFAULT_LABEL_KEY = "passkey.default-label";
    static final String MODEL_PASSKEY = "passkey";
    static final String MODEL_ADDED_AT = "addedAt";

    private static final String SIGN_IN_FAILED = "Passkey sign-in failed";
    private static final String REGISTRATION_FAILED = "The passkey could not be added";
    private static final String ACCOUNT_UNAVAILABLE = "Account unavailable";
    private static final int USER_HANDLE_BYTES = 16;
    private static final int TRANSPORTS_MAX = 200;
    private static final Base64.Encoder BASE64URL = Base64.getUrlEncoder().withoutPadding();
    private static final List<CredentialParameters> CREDENTIAL_PARAMETERS = WebAuthnRelyingParty.algorithmIds().stream()
            .map(alg -> new CredentialParameters(WebAuthnRelyingParty.CREDENTIAL_TYPE, alg))
            .toList();
    private static final AuthenticatorSelection AUTHENTICATOR_SELECTION = new AuthenticatorSelection(
            WebAuthnRelyingParty.RESIDENT_KEY, true, WebAuthnRelyingParty.USER_VERIFICATION);

    private final PasskeyRepository passkeyRepository;
    private final PasskeyChallenges challenges;
    private final WebAuthnRelyingParty relyingParty;
    private final UserRepository userRepository;
    private final AuthService authService;
    private final SettingsService settings;
    private final MessageText messageText;
    private final EventMailer eventMailer;
    private final ObjectMapper objectMapper;
    private final EntityManager entityManager;
    private final Clock clock;

    public PasskeyService(PasskeyRepository passkeyRepository, PasskeyChallenges challenges,
                          WebAuthnRelyingParty relyingParty, UserRepository userRepository, AuthService authService,
                          SettingsService settings, MessageText messageText, EventMailer eventMailer,
                          ObjectMapper objectMapper, EntityManager entityManager, Clock clock) {
        this.passkeyRepository = passkeyRepository;
        this.challenges = challenges;
        this.relyingParty = relyingParty;
        this.userRepository = userRepository;
        this.authService = authService;
        this.settings = settings;
        this.messageText = messageText;
        this.eventMailer = eventMailer;
        this.objectMapper = objectMapper;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ registration (signed in)

    /**
     * Creation options for a new passkey of the caller; {@code requestLocale} (Accept-Language, null = the user's
     * locale) picks the brand name shown as the relying party.
     */
    @Transactional
    public RegistrationOptionsResponse registrationOptions(UUID userId, Locale requestLocale) {
        User user = activeUser(userId);
        Locale locale = requestLocale != null ? requestLocale : user.getLocale();
        PasskeyChallenge challenge = challenges.issue(PasskeyPurpose.REGISTRATION, userId);
        List<CredentialDescriptor> existing = passkeyRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(PasskeyService::descriptor)
                .toList();
        String displayName = user.getName() == null || user.getName().isBlank() ? user.getEmail() : user.getName().trim();
        CreationOptions options = new CreationOptions(
                new RelyingParty(relyingParty.rpId(), rpName(locale)),
                new UserEntity(base64url(userHandle(userId)), user.getEmail(), displayName),
                base64url(challenge.getChallenge()),
                CREDENTIAL_PARAMETERS,
                challenges.ttl().toMillis(),
                existing,
                AUTHENTICATOR_SELECTION,
                WebAuthnRelyingParty.ATTESTATION);
        return new RegistrationOptionsResponse(challenge.getRequestId().toString(), options);
    }

    /**
     * Verifies the new credential against the caller's registration challenge and stores it (201). The label
     * defaults to "&lt;browser&gt; · &lt;os&gt;" of {@code userAgent}.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public PasskeyDto register(UUID userId, PasskeyRegistrationRequest request, String userAgent) {
        User user = activeUser(userId);
        PasskeyChallenge challenge = challenges.consumeRegistration(request.requestId(), userId)
                .orElseThrow(() -> registrationFailed("unknown, used, expired or foreign request " + request.requestId()));
        NewCredential credential;
        try {
            credential = relyingParty.verifyRegistration(credentialJson(request.credential()), challenge.getChallenge());
        } catch (PasskeyVerificationException e) {
            throw registrationFailed(e.getMessage());
        }
        if (passkeyRepository.existsByCredentialId(credential.credentialId())) {
            throw registrationFailed("credential already registered");
        }
        Passkey passkey = new Passkey();
        passkey.setUserId(userId);
        passkey.setCredentialId(credential.credentialId());
        passkey.setPublicKey(credential.publicKeyCose());
        passkey.setSignCount(Math.max(0, credential.signCount()));
        passkey.setAaguid(credential.aaguid());
        passkey.setTransports(transports(credential.transports()));
        passkey.setLabel(label(request.label(), userAgent, user.getLocale()));
        passkey.setCreatedAt(clock.instant());
        passkeyRepository.save(passkey);
        mailPasskeyAdded(user, passkey);
        log.info("Passkey {} added to user {}", passkey.getId(), userId);
        return toDto(passkey);
    }

    // ------------------------------------------------------------------ the caller's passkeys

    /** The caller's passkeys, newest first. */
    @Transactional(readOnly = true)
    public List<PasskeyDto> list(UUID userId) {
        return passkeyRepository.findByUserIdOrderByCreatedAtDesc(userId).stream().map(PasskeyService::toDto).toList();
    }

    /** Removes one of the caller's passkeys; 404 when it is not the caller's. */
    @Transactional
    public void delete(UUID userId, UUID passkeyId) {
        Passkey passkey = passkeyRepository.findByIdAndUserId(passkeyId, userId)
                .orElseThrow(() -> NotFoundException.of("Passkey", passkeyId));
        passkeyRepository.delete(passkey);
        log.info("Passkey {} of user {} removed", passkeyId, userId);
    }

    // ------------------------------------------------------------------ sign-in (anonymous)

    /** Request options with a single-use challenge and no {@code allowCredentials} (the device offers its passkeys). */
    @Transactional
    public SignInOptionsResponse signInOptions() {
        PasskeyChallenge challenge = challenges.issue(PasskeyPurpose.AUTHENTICATION, null);
        RequestOptions options = new RequestOptions(
                base64url(challenge.getChallenge()),
                challenges.ttl().toMillis(),
                relyingParty.rpId(),
                List.of(),
                WebAuthnRelyingParty.USER_VERIFICATION);
        return new SignInOptionsResponse(challenge.getRequestId().toString(), options);
    }

    /**
     * Signs in with a passkey: consumes the challenge, finds the passkey by credential ID, checks the user handle
     * against its (active) owner, verifies the assertion, updates the counter and last use, then completes the
     * sign-in like every other one. 401 {@link #CODE_INVALID} on any failure.
     */
    @Transactional(noRollbackFor = UnauthorizedException.class)
    public LoginResult signIn(PasskeySignInRequest request, LoginContext ctx) {
        PasskeyChallenge challenge = challenges.consumeSignIn(request.requestId())
                .orElseThrow(() -> signInFailed("unknown, used or expired request " + request.requestId()));
        Assertion assertion;
        try {
            assertion = relyingParty.readAssertion(credentialJson(request.credential()));
        } catch (PasskeyVerificationException e) {
            throw signInFailed(e.getMessage());
        }
        Passkey passkey = passkeyRepository.findByCredentialId(assertion.credentialId())
                .orElseThrow(() -> signInFailed("unknown credential"));
        // one sign-in at a time per passkey (signature counter)
        entityManager.refresh(passkey, LockModeType.PESSIMISTIC_WRITE);
        User user = userRepository.findById(passkey.getUserId())
                .filter(u -> !u.isDeleted())
                .orElseThrow(() -> signInFailed("the owner of passkey " + passkey.getId() + " is unavailable"));
        if (assertion.userHandle() == null || !Arrays.equals(assertion.userHandle(), userHandle(user.getId()))) {
            throw signInFailed("user handle does not match the owner of passkey " + passkey.getId());
        }
        long signCount;
        try {
            signCount = relyingParty.verifyAssertion(assertion, challenge.getChallenge(), stored(passkey));
        } catch (PasskeyVerificationException e) {
            throw signInFailed("passkey " + passkey.getId() + ": " + e.getMessage());
        }
        Instant now = clock.instant();
        passkey.setSignCount(Math.max(passkey.getSignCount(), signCount));
        passkey.setLastUsedAt(now);
        passkeyRepository.save(passkey);
        log.info("User {} signed in with passkey {}", user.getId(), passkey.getId());
        return authService.loginWithPasskey(user, AuthDtos.rememberMe(request.rememberMe()), ctx);
    }

    // ------------------------------------------------------------------ helpers

    /**
     * The WebAuthn user handle of an account: the 16 bytes of its id (opaque, no personal data). A sign-in must
     * present the handle of the passkey's owner.
     */
    public static byte[] userHandle(UUID userId) {
        return ByteBuffer.allocate(USER_HANDLE_BYTES)
                .putLong(userId.getMostSignificantBits())
                .putLong(userId.getLeastSignificantBits())
                .array();
    }

    static PasskeyDto toDto(Passkey passkey) {
        return new PasskeyDto(passkey.getId(), passkey.getLabel(), passkey.getCreatedAt(), passkey.getLastUsedAt());
    }

    /**
     * The active account; deliberately not AccountService.requireActive: a 401 thrown through another transactional
     * bean would mark the transaction rollback-only and lose the consumed challenge.
     */
    private User activeUser(UUID userId) {
        return userRepository.findById(userId)
                .filter(u -> !u.isDeleted())
                .orElseThrow(() -> new UnauthorizedException(ACCOUNT_UNAVAILABLE, AuthService.CODE_ACCOUNT_UNAVAILABLE));
    }

    /** {@code passkey-added} to the account owner after commit (switch {@code mail.event.passkey-added}). */
    private void mailPasskeyAdded(User user, Passkey passkey) {
        eventMailer.toUser(user, MailTemplates.PASSKEY_ADDED, passkeyAddedModel(user, passkey),
                MailTemplates.PASSKEY_ADDED + ":" + passkey.getId());
    }

    /**
     * Model of the {@code passkey-added} e-mail: the passkey's label, when it was added and the passkeys section of
     * the account's own profile page ({@code /me/profile#passkeys} or {@code /admin/profile#passkeys}).
     */
    Map<String, Object> passkeyAddedModel(User user, Passkey passkey) {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put(MODEL_PASSKEY, passkey.getLabel());
        model.put(MODEL_ADDED_AT, passkey.getCreatedAt());
        model.put(MailService.MODEL_LINK, FrontendPaths.passkeys(user.getRole() == Role.INTERPRETER));
        return model;
    }

    private String rpName(Locale locale) {
        String name = settings.getString(locale == Locale.EN ? SettingKeys.BRAND_NAME_EN : SettingKeys.BRAND_NAME_AR, "");
        return name == null || name.isBlank() ? relyingParty.rpId() : name.trim();
    }

    private String label(String requested, String userAgent, Locale locale) {
        String label = requested == null || requested.isBlank()
                ? messageText.get(DEFAULT_LABEL_KEY, locale,
                        Map.of("browser", UserAgents.browser(userAgent), "os", UserAgents.os(userAgent)))
                : requested.trim();
        return label.length() > PasskeyDtos.LABEL_MAX ? label.substring(0, PasskeyDtos.LABEL_MAX) : label;
    }

    private String credentialJson(JsonNode credential) {
        if (credential == null || !credential.isObject()) {
            throw new PasskeyVerificationException("the credential is not a JSON object");
        }
        try {
            return objectMapper.writeValueAsString(credential);
        } catch (JsonProcessingException e) {
            throw new PasskeyVerificationException("the credential cannot be read", e);
        }
    }

    private static StoredCredential stored(Passkey passkey) {
        return new StoredCredential(passkey.getCredentialId(), passkey.getPublicKey(), passkey.getSignCount(),
                passkey.getAaguid());
    }

    private static CredentialDescriptor descriptor(Passkey passkey) {
        List<String> transports = passkey.transportList();
        return new CredentialDescriptor(WebAuthnRelyingParty.CREDENTIAL_TYPE, base64url(passkey.getCredentialId()),
                transports.isEmpty() ? null : transports);
    }

    private static String transports(List<String> transports) {
        if (transports == null || transports.isEmpty()) {
            return null;
        }
        String joined = String.join(Passkey.TRANSPORT_SEPARATOR, transports);
        return joined.length() > TRANSPORTS_MAX ? null : joined;
    }

    private static String base64url(byte[] bytes) {
        return BASE64URL.encodeToString(bytes);
    }

    private static ValidationException registrationFailed(String reason) {
        log.info("Passkey registration rejected: {}", reason);
        return new ValidationException(REGISTRATION_FAILED, CODE_INVALID);
    }

    private static UnauthorizedException signInFailed(String reason) {
        log.info("Passkey sign-in rejected: {}", reason);
        return new UnauthorizedException(SIGN_IN_FAILED, CODE_INVALID);
    }
}
