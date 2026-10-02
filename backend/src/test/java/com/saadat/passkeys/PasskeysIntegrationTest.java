package com.saadat.passkeys;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.saadat.auth.SessionTestBase;
import com.saadat.common.api.ApiPaths;
import com.saadat.common.domain.Role;
import com.saadat.mail.MailTemplates;
import com.saadat.notifications.domain.EmailLog;
import com.saadat.notifications.repo.EmailLogRepository;
import com.saadat.passkeys.domain.Passkey;
import com.saadat.passkeys.domain.PasskeyChallenge;
import com.saadat.passkeys.repo.PasskeyChallengeRepository;
import com.saadat.passkeys.repo.PasskeyRepository;
import com.saadat.passkeys.service.PasskeyService;
import com.saadat.settings.SettingKeys;
import com.saadat.settings.SettingsService;
import com.saadat.users.domain.User;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Passkeys end to end (docs/PASSKEYS_CONTRACT.md) with an emulated authenticator (real keys and signatures): adding a
 * passkey from a signed-in session, listing and removing it, signing in with it exactly like a magic-link sign-in
 * (refresh cookie, device sid, remember me, role sync, new-sign-in alert), the passkey-added e-mail, and every way a
 * sign-in must fail with 401 PASSKEY_INVALID.
 */
class PasskeysIntegrationTest extends SessionTestBase {

    /** app.frontend-url of application-test.yml: RP ID "localhost", the only allowed origin. */
    private static final String ORIGIN = "http://localhost:5173";
    private static final String PHISHING_ORIGIN = "https://saadatu-aldarein.example.com";
    private static final String INTERPRETER_DOMAIN = "@saadatu-aldarein.com";

    @Autowired
    PasskeyRepository passkeyRepository;

    @Autowired
    PasskeyChallengeRepository challengeRepository;

    @Autowired
    EmailLogRepository emailLogRepository;

    @Autowired
    SettingsService settingsService;

    // ------------------------------------------------------------------ registration and sign-in

    @Test
    void aUserAddsAPasskeyAndSignsInWithIt() throws Exception {
        String email = uniqueEmail("passkey-user");
        SignIn session = signIn(email, CHROME_WINDOWS, "EG", null);
        User user = userRepository.findByEmailIgnoreCase(email).orElseThrow();

        JsonNode options = registrationOptions(session.bearer());
        JsonNode publicKey = options.get("publicKey");
        assertThat(options.get("requestId").asText()).isNotBlank();
        assertThat(publicKey.get("rp").get("id").asText()).isEqualTo("localhost");
        assertThat(publicKey.get("rp").get("name").asText())
                .isEqualTo(settingsService.getString(SettingKeys.BRAND_NAME_AR));
        assertThat(publicKey.get("user").get("id").asText())
                .isEqualTo(TestPasskeyDevice.base64url(PasskeyService.userHandle(user.getId())));
        assertThat(publicKey.get("user").get("name").asText()).isEqualTo(email);
        assertThat(publicKey.get("user").get("displayName").asText()).isNotBlank();
        assertThat(Base64.getUrlDecoder().decode(publicKey.get("challenge").asText())).hasSize(32);
        assertThat(publicKey.get("pubKeyCredParams").get(0).get("type").asText()).isEqualTo("public-key");
        assertThat(publicKey.get("pubKeyCredParams").get(0).get("alg").asLong()).isEqualTo(-7L);
        assertThat(publicKey.get("timeout").asLong()).isEqualTo(300_000L);
        assertThat(publicKey.get("excludeCredentials").size()).isZero();
        assertThat(publicKey.get("authenticatorSelection").get("residentKey").asText()).isEqualTo("required");
        assertThat(publicKey.get("authenticatorSelection").get("userVerification").asText()).isEqualTo("required");
        assertThat(publicKey.get("attestation").asText()).isEqualTo("none");

        TestPasskeyDevice phone = new TestPasskeyDevice(ORIGIN);
        Map<String, Object> credential = phone.create(publicKey.toString());
        JsonNode added = body(postRegistration(session.bearer(), options.get("requestId").asText(), credential, null,
                SAFARI_IPHONE).andExpect(status().isCreated()).andReturn());
        assertThat(added.get("label").asText()).isEqualTo("Safari · iOS");
        assertThat(Instant.parse(added.get("createdAt").asText())).isBeforeOrEqualTo(Instant.now());
        assertThat(added.get("lastUsedAt").isNull()).isTrue();
        Passkey stored = passkeyRepository.findById(UUID.fromString(added.get("id").asText())).orElseThrow();
        assertThat(stored.getUserId()).isEqualTo(user.getId());
        assertThat(TestPasskeyDevice.base64url(stored.getCredentialId())).isEqualTo(credential.get("rawId"));
        assertThat(stored.getSignCount()).isEqualTo(1);
        assertThat(stored.transportList()).containsExactly("hybrid", "internal");

        // anonymous sign-in on the phone, without "remember me"
        JsonNode signInOptions = signInOptions();
        JsonNode requestOptions = signInOptions.get("publicKey");
        assertThat(requestOptions.get("rpId").asText()).isEqualTo("localhost");
        assertThat(requestOptions.get("allowCredentials").size()).isZero();
        assertThat(requestOptions.get("userVerification").asText()).isEqualTo("required");
        assertThat(requestOptions.get("timeout").asLong()).isEqualTo(300_000L);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", signInOptions.get("requestId").asText());
        body.put("credential", phone.get(requestOptions.toString()));
        body.put("rememberMe", false);
        MvcResult result = verify(body, SAFARI_IPHONE)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expiresIn").isNumber())
                .andExpect(jsonPath("$.user.id").value(user.getId().toString()))
                .andExpect(jsonPath("$.user.email").value(email))
                .andExpect(jsonPath("$.user.role").value("USER"))
                .andReturn();
        SignIn passkeySession = read(result.getResponse());
        assertThat(passkeySession.familyId()).isNotEqualTo(session.familyId());
        assertThat(passkeySession.setCookie()).doesNotContain("Max-Age").contains("HttpOnly");
        assertThat(row(passkeySession.refreshToken()).isPersistent()).isFalse();
        mvc.perform(get(ApiPaths.Me.ROOT).header(HttpHeaders.AUTHORIZATION, passkeySession.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email));

        Passkey used = passkeyRepository.findById(stored.getId()).orElseThrow();
        assertThat(used.getSignCount()).isEqualTo(2);
        assertThat(used.getLastUsedAt()).isNotNull();
        mvc.perform(get(ApiPaths.Me.PASSKEYS).header(HttpHeaders.AUTHORIZATION, passkeySession.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(stored.getId().toString()))
                .andExpect(jsonPath("$[0].label").value("Safari · iOS"))
                .andExpect(jsonPath("$[0].createdAt").isNotEmpty())
                .andExpect(jsonPath("$[0].lastUsedAt").isNotEmpty());

        // a browser + system this account never used: the new-sign-in alert, like any other sign-in
        awaitTrue("new-sign-in e-mail", () -> emailLogRepository.existsByTemplateAndRef(MailTemplates.NEW_SIGN_IN,
                MailTemplates.NEW_SIGN_IN + ":" + passkeySession.familyId()));
    }

    @Test
    void theInterpreterAddsAPasskeyAndSignsInWithIt() throws Exception {
        // interpreter accounts get their session from the e-mail code on the site domain, then add a passkey
        String email = "passkey-" + UUID.randomUUID() + INTERPRETER_DOMAIN;
        SignIn session = signIn(email, SAFARI_MAC, "SA", null);
        TestPasskeyDevice mac = new TestPasskeyDevice(ORIGIN);
        JsonNode added = addedPasskey(session.bearer(), mac);

        MvcResult result = verify(signInBody(mac, null), SAFARI_MAC)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.role").value("INTERPRETER"))
                .andReturn();
        SignIn passkeySession = read(result.getResponse());
        // "remember me" is the default
        assertThat(passkeySession.setCookie()).contains("Max-Age=");
        assertThat(row(passkeySession.refreshToken()).isPersistent()).isTrue();
        mvc.perform(get(ApiPaths.Admin.ANALYTICS_SUMMARY).header(HttpHeaders.AUTHORIZATION, passkeySession.bearer()))
                .andExpect(status().isOk());

        awaitTrue("passkey-added e-mail", () -> mailed(added));
    }

    @Test
    void signingInWithAPasskeyAppliesTheInterpreterRules() throws Exception {
        // an account on the interpreter domain still stored as USER: the role follows the rules on every sign-in
        User user = new User();
        user.setEmail("passkey-role-" + UUID.randomUUID() + INTERPRETER_DOMAIN);
        user.setName("Fatema");
        user.setRole(Role.USER);
        user.setOnboarded(true);
        user = userRepository.save(user);
        TestPasskeyDevice device = new TestPasskeyDevice(ORIGIN);
        addedPasskey(bearer(user), device);

        verify(signInBody(device, null), SAFARI_MAC)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.role").value("INTERPRETER"));
        assertThat(userRepository.findById(user.getId()).orElseThrow().getRole()).isEqualTo(Role.INTERPRETER);
    }

    @Test
    void registrationOptionsExcludeTheCallersPasskeys() throws Exception {
        SignIn session = signIn(uniqueEmail("passkey-exclude"), CHROME_WINDOWS, "EG", null);
        TestPasskeyDevice laptop = new TestPasskeyDevice(ORIGIN);
        JsonNode added = addedPasskey(session.bearer(), laptop);
        Passkey stored = passkeyRepository.findById(UUID.fromString(added.get("id").asText())).orElseThrow();

        JsonNode again = registrationOptions(session.bearer());
        JsonNode exclude = again.get("publicKey").get("excludeCredentials");
        assertThat(exclude.size()).isEqualTo(1);
        assertThat(exclude.get(0).get("type").asText()).isEqualTo("public-key");
        assertThat(exclude.get(0).get("id").asText()).isEqualTo(TestPasskeyDevice.base64url(stored.getCredentialId()));
        assertThat(exclude.get(0).get("transports").size()).isEqualTo(2);
        // the device that already holds one refuses to create a second passkey for the account (InvalidStateError)
        assertThatThrownBy(() -> laptop.create(again.get("publicKey").toString())).isInstanceOf(RuntimeException.class);

        SignIn other = signIn(uniqueEmail("passkey-exclude-other"), CHROME_WINDOWS, "EG", null);
        assertThat(registrationOptions(other.bearer()).get("publicKey").get("excludeCredentials").size()).isZero();
    }

    @Test
    void listsAndRemovesOwnPasskeysOnly() throws Exception {
        SignIn mine = signIn(uniqueEmail("passkey-mine"), CHROME_WINDOWS, "EG", null);
        SignIn theirs = signIn(uniqueEmail("passkey-theirs"), FIREFOX_LINUX, "SA", null);
        TestPasskeyDevice laptop = new TestPasskeyDevice(ORIGIN);
        JsonNode first = body(addPasskey(mine.bearer(), laptop, "  My laptop  ", CHROME_WINDOWS)
                .andExpect(status().isCreated()).andReturn());
        assertThat(first.get("label").asText()).isEqualTo("My laptop");
        JsonNode second = addedPasskey(mine.bearer(), new TestPasskeyDevice(ORIGIN));
        JsonNode theirsKey = addedPasskey(theirs.bearer(), new TestPasskeyDevice(ORIGIN));

        JsonNode list = passkeys(mine);
        assertThat(list.size()).isEqualTo(2);
        assertThat(list.get(0).get("id").asText()).isEqualTo(second.get("id").asText()); // newest first
        assertThat(list.get(0).get("label").asText()).isEqualTo("Chrome · Windows");
        assertThat(list.get(1).get("id").asText()).isEqualTo(first.get("id").asText());

        // someone else's passkey (or an unknown id) is 404 and stays
        mvc.perform(delete(ApiPaths.Me.PASSKEYS + "/" + theirsKey.get("id").asText())
                        .header(HttpHeaders.AUTHORIZATION, mine.bearer()))
                .andExpect(status().isNotFound());
        mvc.perform(delete(ApiPaths.Me.PASSKEYS + "/" + UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, mine.bearer()))
                .andExpect(status().isNotFound());
        assertThat(passkeyRepository.existsById(UUID.fromString(theirsKey.get("id").asText()))).isTrue();
        assertThat(passkeys(theirs).size()).isEqualTo(1);

        mvc.perform(delete(ApiPaths.Me.PASSKEYS + "/" + first.get("id").asText())
                        .header(HttpHeaders.AUTHORIZATION, mine.bearer()))
                .andExpect(status().isNoContent());
        JsonNode left = passkeys(mine);
        assertThat(left.size()).isEqualTo(1);
        assertThat(left.get(0).get("id").asText()).isEqualTo(second.get("id").asText());
        // a removed passkey no longer signs in
        expectInvalidSignIn(signInBody(laptop, null));
    }

    // ------------------------------------------------------------------ refused sign-ins and registrations

    @Test
    void aChallengeWorksOnceAndOnlyForItsPurpose() throws Exception {
        String email = uniqueEmail("passkey-once");
        SignIn session = signIn(email, CHROME_WINDOWS, "EG", null);
        UUID userId = userRepository.findByEmailIgnoreCase(email).orElseThrow().getId();
        TestPasskeyDevice device = new TestPasskeyDevice(ORIGIN);
        JsonNode options = registrationOptions(session.bearer());
        String requestId = options.get("requestId").asText();
        Map<String, Object> credential = device.create(options.get("publicKey").toString());

        postRegistration(session.bearer(), requestId, credential, null, CHROME_WINDOWS).andExpect(status().isCreated());
        postRegistration(session.bearer(), requestId, credential, null, CHROME_WINDOWS)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(PasskeyService.CODE_INVALID));
        assertThat(passkeyRepository.countByUserId(userId)).isEqualTo(1);

        Map<String, Object> body = signInBody(device, null);
        verify(body, CHROME_WINDOWS).andExpect(status().isOk());
        expectInvalidSignIn(body); // replayed

        // a registration requestId cannot sign in, a sign-in requestId cannot register
        Map<String, Object> wrongPurpose = signInBody(device, null);
        wrongPurpose.put("requestId", registrationOptions(session.bearer()).get("requestId").asText());
        expectInvalidSignIn(wrongPurpose);
        JsonNode registration = registrationOptions(session.bearer());
        Map<String, Object> other = new TestPasskeyDevice(ORIGIN).create(registration.get("publicKey").toString());
        postRegistration(session.bearer(), signInOptions().get("requestId").asText(), other, null, CHROME_WINDOWS)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(PasskeyService.CODE_INVALID));
        // another account's registration requestId cannot be used either
        SignIn stranger = signIn(uniqueEmail("passkey-once-stranger"), CHROME_WINDOWS, "EG", null);
        postRegistration(stranger.bearer(), registration.get("requestId").asText(), other, null, CHROME_WINDOWS)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(PasskeyService.CODE_INVALID));
    }

    @Test
    void anExpiredChallengeIsRefused() throws Exception {
        SignIn session = signIn(uniqueEmail("passkey-expired"), CHROME_WINDOWS, "EG", null);
        TestPasskeyDevice device = new TestPasskeyDevice(ORIGIN);
        addedPasskey(session.bearer(), device);

        Map<String, Object> body = signInBody(device, null);
        expire((String) body.get("requestId"));
        expectInvalidSignIn(body);

        JsonNode options = registrationOptions(session.bearer());
        Map<String, Object> credential = new TestPasskeyDevice(ORIGIN).create(options.get("publicKey").toString());
        expire(options.get("requestId").asText());
        postRegistration(session.bearer(), options.get("requestId").asText(), credential, null, CHROME_WINDOWS)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(PasskeyService.CODE_INVALID));
    }

    @Test
    void aPhishingOriginIsRefused() throws Exception {
        SignIn session = signIn(uniqueEmail("passkey-origin"), CHROME_WINDOWS, "EG", null);
        TestPasskeyDevice device = new TestPasskeyDevice(ORIGIN);
        addedPasskey(session.bearer(), device);

        device.useOrigin(PHISHING_ORIGIN);
        expectInvalidSignIn(signInBody(device, null));
        addPasskey(session.bearer(), new TestPasskeyDevice(PHISHING_ORIGIN), null, CHROME_WINDOWS)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(PasskeyService.CODE_INVALID));

        // back on the real site the passkey still works
        device.useOrigin(ORIGIN);
        verify(signInBody(device, null), CHROME_WINDOWS).andExpect(status().isOk());
    }

    @Test
    void aTamperedSignatureOrAnotherUsersHandleIsRefused() throws Exception {
        SignIn session = signIn(uniqueEmail("passkey-tampered"), CHROME_WINDOWS, "EG", null);
        TestPasskeyDevice device = new TestPasskeyDevice(ORIGIN);
        addedPasskey(session.bearer(), device);

        Map<String, Object> tampered = signInBody(device, null);
        Map<String, Object> response = response(tampered);
        byte[] signature = Base64.getUrlDecoder().decode((String) response.get("signature"));
        signature[signature.length - 1] ^= 0x01;
        response.put("signature", TestPasskeyDevice.base64url(signature));
        expectInvalidSignIn(tampered);

        Map<String, Object> foreignHandle = signInBody(device, null);
        User other = createUser("passkey-other-handle", Role.USER);
        response(foreignHandle).put("userHandle", TestPasskeyDevice.base64url(PasskeyService.userHandle(other.getId())));
        expectInvalidSignIn(foreignHandle);

        Map<String, Object> noHandle = signInBody(device, null);
        response(noHandle).put("userHandle", null);
        expectInvalidSignIn(noHandle);
    }

    @Test
    void anUnknownPasskeyIsRefused() throws Exception {
        SignIn session = signIn(uniqueEmail("passkey-unknown"), CHROME_WINDOWS, "EG", null);
        // created on the device but never sent to the server
        TestPasskeyDevice stranger = new TestPasskeyDevice(ORIGIN);
        stranger.create(registrationOptions(session.bearer()).get("publicKey").toString());
        expectInvalidSignIn(signInBody(stranger, null));
    }

    @Test
    void aSignatureCounterThatDoesNotGrowIsRefused() throws Exception {
        SignIn session = signIn(uniqueEmail("passkey-counter"), CHROME_WINDOWS, "EG", null);
        TestPasskeyDevice key = new TestPasskeyDevice(ORIGIN);
        UUID id = UUID.fromString(addedPasskey(session.bearer(), key).get("id").asText());
        verify(signInBody(key, null), CHROME_WINDOWS).andExpect(status().isOk());
        assertThat(passkeyRepository.findById(id).orElseThrow().getSignCount()).isEqualTo(2);

        key.freezeCounter(); // presents 2 again, like a cloned key
        expectInvalidSignIn(signInBody(key, null));
        assertThat(passkeyRepository.findById(id).orElseThrow().getSignCount()).isEqualTo(2);

        // passkeys without a counter (always 0, e.g. synced ones) keep working
        TestPasskeyDevice synced = TestPasskeyDevice.withoutCounter(ORIGIN);
        addedPasskey(session.bearer(), synced);
        verify(signInBody(synced, null), CHROME_WINDOWS).andExpect(status().isOk());
        verify(signInBody(synced, null), CHROME_WINDOWS).andExpect(status().isOk());
    }

    @Test
    void malformedSignInsAreRefusedTheSameWay() throws Exception {
        Map<String, Object> noRequest = new LinkedHashMap<>();
        noRequest.put("credential", Map.of("id", "abc"));
        expectInvalidSignIn(noRequest);

        Map<String, Object> notAnObject = new LinkedHashMap<>();
        notAnObject.put("requestId", signInOptions().get("requestId").asText());
        notAnObject.put("credential", "nope");
        expectInvalidSignIn(notAnObject);

        Map<String, Object> badRequestId = new LinkedHashMap<>();
        badRequestId.put("requestId", "not-a-uuid");
        badRequestId.put("credential", Map.of());
        expectInvalidSignIn(badRequestId);
    }

    @Test
    void deletingTheAccountDeletesItsPasskeys() throws Exception {
        String email = uniqueEmail("passkey-delete");
        SignIn session = signIn(email, CHROME_WINDOWS, "EG", null);
        UUID userId = userRepository.findByEmailIgnoreCase(email).orElseThrow().getId();
        TestPasskeyDevice device = new TestPasskeyDevice(ORIGIN);
        addedPasskey(session.bearer(), device);
        assertThat(passkeyRepository.countByUserId(userId)).isEqualTo(1);

        mvc.perform(delete(ApiPaths.Me.ROOT).header(HttpHeaders.AUTHORIZATION, session.bearer()))
                .andExpect(status().isNoContent());

        assertThat(passkeyRepository.countByUserId(userId)).isZero();
        expectInvalidSignIn(signInBody(device, null));
    }

    @Test
    void thePasskeyRoutesOfTheAccountNeedASignedInCaller() throws Exception {
        mvc.perform(get(ApiPaths.Me.PASSKEYS)).andExpect(status().isUnauthorized());
        mvc.perform(post(ApiPaths.Me.PASSKEY_REGISTRATION_OPTIONS)).andExpect(status().isUnauthorized());
        mvc.perform(post(ApiPaths.Me.PASSKEY_REGISTRATION).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(delete(ApiPaths.Me.PASSKEYS + "/" + UUID.randomUUID())).andExpect(status().isUnauthorized());
        // the sign-in routes are open
        mvc.perform(post(ApiPaths.Auth.PASSKEY_OPTIONS)).andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ passkey-added e-mail

    @Test
    void thePasskeyAddedMailIsSentUnlessSwitchedOff() throws Exception {
        String email = uniqueEmail("passkey-mail");
        SignIn session = signIn(email, CHROME_WINDOWS, "EG", null);
        JsonNode silent;
        settingsService.put(SettingKeys.MAIL_EVENT_PASSKEY_ADDED, "false", null);
        try {
            silent = addedPasskey(session.bearer(), new TestPasskeyDevice(ORIGIN));
        } finally {
            settingsService.put(SettingKeys.MAIL_EVENT_PASSKEY_ADDED, "true", null);
        }
        // switched on again: the next passkey is mailed (and proves the after-commit work of the first one ran)
        JsonNode mailed = addedPasskey(session.bearer(), new TestPasskeyDevice(ORIGIN));

        awaitTrue("passkey-added e-mail", () -> mailed(mailed));
        Thread.sleep(500);
        assertThat(mailed(silent)).isFalse();
        List<EmailLog> rows = emailLogRepository.findByToEmailOrderByCreatedAtDesc(email).stream()
                .filter(r -> MailTemplates.PASSKEY_ADDED.equals(r.getTemplate()))
                .toList();
        assertThat(rows).hasSize(1);
        // the account was created without Accept-Language, so it reads Arabic
        assertThat(rows.get(0).getSubject()).isEqualTo("أُضيف مفتاح مرور إلى حسابك");
    }

    // ------------------------------------------------------------------ helpers

    private JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode registrationOptions(String bearer) throws Exception {
        return body(mvc.perform(post(ApiPaths.Me.PASSKEY_REGISTRATION_OPTIONS).header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andReturn());
    }

    private ResultActions postRegistration(String bearer, String requestId, Object credential, String label,
                                           String userAgent) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("credential", credential);
        if (label != null) {
            body.put("label", label);
        }
        MockHttpServletRequestBuilder request = post(ApiPaths.Me.PASSKEY_REGISTRATION)
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body));
        if (userAgent != null) {
            request.header(HttpHeaders.USER_AGENT, userAgent);
        }
        return mvc.perform(request);
    }

    /** Fresh options → the device creates a passkey → POST /me/passkeys/registration. */
    private ResultActions addPasskey(String bearer, TestPasskeyDevice device, String label, String userAgent)
            throws Exception {
        JsonNode options = registrationOptions(bearer);
        Map<String, Object> credential = device.create(options.get("publicKey").toString());
        return postRegistration(bearer, options.get("requestId").asText(), credential, label, userAgent);
    }

    private JsonNode addedPasskey(String bearer, TestPasskeyDevice device) throws Exception {
        return body(addPasskey(bearer, device, null, CHROME_WINDOWS).andExpect(status().isCreated()).andReturn());
    }

    private JsonNode passkeys(SignIn session) throws Exception {
        return body(mvc.perform(get(ApiPaths.Me.PASSKEYS).header(HttpHeaders.AUTHORIZATION, session.bearer()))
                .andExpect(status().isOk())
                .andReturn());
    }

    private JsonNode signInOptions() throws Exception {
        return body(mvc.perform(post(ApiPaths.Auth.PASSKEY_OPTIONS)).andExpect(status().isOk()).andReturn());
    }

    /** What the browser posts to /auth/passkey/verify after the device answered fresh options. */
    private Map<String, Object> signInBody(TestPasskeyDevice device, Boolean rememberMe) throws Exception {
        JsonNode options = signInOptions();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", options.get("requestId").asText());
        body.put("credential", device.get(options.get("publicKey").toString()));
        if (rememberMe != null) {
            body.put("rememberMe", rememberMe);
        }
        return body;
    }

    private ResultActions verify(Map<String, Object> body, String userAgent) throws Exception {
        MockHttpServletRequestBuilder request = post(ApiPaths.Auth.PASSKEY_VERIFY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body));
        if (userAgent != null) {
            request.header(HttpHeaders.USER_AGENT, userAgent);
        }
        return mvc.perform(request);
    }

    private void expectInvalidSignIn(Map<String, Object> body) throws Exception {
        verify(body, CHROME_WINDOWS)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(PasskeyService.CODE_INVALID))
                .andExpect(jsonPath("$.status").value(401));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> response(Map<String, Object> body) {
        return (Map<String, Object>) ((Map<String, Object>) body.get("credential")).get("response");
    }

    private void expire(String requestId) {
        PasskeyChallenge challenge = challengeRepository.findById(UUID.fromString(requestId)).orElseThrow();
        challenge.setExpiresAt(Instant.now().minusSeconds(1));
        challengeRepository.save(challenge);
    }

    private boolean mailed(JsonNode passkey) {
        return emailLogRepository.existsByTemplateAndRef(MailTemplates.PASSKEY_ADDED,
                MailTemplates.PASSKEY_ADDED + ":" + passkey.get("id").asText());
    }
}
