package com.saadat.passkeys;

import com.webauthn4j.converter.util.ObjectConverter;
import com.webauthn4j.data.AuthenticatorAssertionResponse;
import com.webauthn4j.data.AuthenticatorAttestationResponse;
import com.webauthn4j.data.PublicKeyCredential;
import com.webauthn4j.data.PublicKeyCredentialCreationOptions;
import com.webauthn4j.data.PublicKeyCredentialRequestOptions;
import com.webauthn4j.data.client.Origin;
import com.webauthn4j.data.extension.client.AuthenticationExtensionClientOutput;
import com.webauthn4j.data.extension.client.RegistrationExtensionClientOutput;
import com.webauthn4j.test.authenticator.webauthn.NoneAttestationAuthenticator;
import com.webauthn4j.test.authenticator.webauthn.WebAuthnAuthenticatorAdaptor;
import com.webauthn4j.test.client.ClientPlatform;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A device with a passkey provider (fingerprint / face sensor), driven the way the browser drives it: the server's
 * {@code publicKey} options go to {@code navigator.credentials.create()/get()} (webauthn4j-test emulator: real EC
 * P-256 keys, real authenticator data and signatures, user verified) and the result comes back in the
 * {@code credential.toJSON()} form the frontend posts.
 *
 * <p>The emulator keeps one signature counter for all its passkeys (+1 per create/get), like a security key.
 */
public final class TestPasskeyDevice {

    private static final Base64.Encoder BASE64URL = Base64.getUrlEncoder().withoutPadding();
    private static final String TYPE = "public-key";
    private static final String PLATFORM = "platform";
    private static final List<String> TRANSPORTS = List.of("hybrid", "internal");

    private final ObjectConverter converter = new ObjectConverter();
    private final NoneAttestationAuthenticator authenticator;
    private final ClientPlatform platform;

    /** The device used from a page on {@code origin} (the origin the browser writes into clientDataJSON). */
    public TestPasskeyDevice(String origin) {
        this(origin, new NoneAttestationAuthenticator());
    }

    private TestPasskeyDevice(String origin, NoneAttestationAuthenticator authenticator) {
        this.authenticator = authenticator;
        this.platform = new ClientPlatform(new Origin(origin), new WebAuthnAuthenticatorAdaptor(authenticator));
    }

    /** A device whose passkeys report no signature counter (always 0), like synced passkeys. */
    public static TestPasskeyDevice withoutCounter(String origin) {
        TestPasskeyDevice device = new TestPasskeyDevice(origin);
        device.freezeCounter();
        return device;
    }

    /** The same device (same passkeys) used from a page on another origin, e.g. a phishing copy of the site. */
    public void useOrigin(String origin) {
        platform.setOrigin(new Origin(origin));
    }

    /** {@code navigator.credentials.create({publicKey})} + {@code toJSON()}; {@code publicKeyJson} = options.publicKey. */
    public Map<String, Object> create(String publicKeyJson) {
        PublicKeyCredentialCreationOptions options =
                converter.getJsonMapper().readValue(publicKeyJson, PublicKeyCredentialCreationOptions.class);
        PublicKeyCredential<AuthenticatorAttestationResponse, RegistrationExtensionClientOutput> credential =
                platform.create(options);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("clientDataJSON", base64url(credential.getResponse().getClientDataJSON()));
        response.put("attestationObject", base64url(credential.getResponse().getAttestationObject()));
        response.put("transports", TRANSPORTS);
        return toJson(credential.getRawId(), response);
    }

    /** {@code navigator.credentials.get({publicKey})} + {@code toJSON()}; {@code publicKeyJson} = options.publicKey. */
    public Map<String, Object> get(String publicKeyJson) {
        PublicKeyCredentialRequestOptions options =
                converter.getJsonMapper().readValue(publicKeyJson, PublicKeyCredentialRequestOptions.class);
        PublicKeyCredential<AuthenticatorAssertionResponse, AuthenticationExtensionClientOutput> credential =
                platform.get(options);
        AuthenticatorAssertionResponse assertion = credential.getResponse();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("clientDataJSON", base64url(assertion.getClientDataJSON()));
        response.put("authenticatorData", base64url(assertion.getAuthenticatorData()));
        response.put("signature", base64url(assertion.getSignature()));
        response.put("userHandle", assertion.getUserHandle() == null ? null : base64url(assertion.getUserHandle()));
        return toJson(credential.getRawId(), response);
    }

    /** From now on the signature counter stays where it is (what a cloned key replaying an old value looks like). */
    public void freezeCounter() {
        authenticator.setCountUpEnabled(false);
    }

    public static String base64url(byte[] bytes) {
        return BASE64URL.encodeToString(bytes);
    }

    private static Map<String, Object> toJson(byte[] rawId, Map<String, Object> response) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", base64url(rawId));
        json.put("rawId", base64url(rawId));
        json.put("type", TYPE);
        json.put("authenticatorAttachment", PLATFORM);
        json.put("clientExtensionResults", new LinkedHashMap<>());
        json.put("response", response);
        return json;
    }
}
