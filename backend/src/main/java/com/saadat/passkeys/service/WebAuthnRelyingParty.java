package com.saadat.passkeys.service;

import com.webauthn4j.WebAuthnManager;
import com.webauthn4j.converter.util.ObjectConverter;
import com.webauthn4j.credential.CredentialRecord;
import com.webauthn4j.credential.CredentialRecordImpl;
import com.webauthn4j.data.AttestationConveyancePreference;
import com.webauthn4j.data.AuthenticationData;
import com.webauthn4j.data.AuthenticationParameters;
import com.webauthn4j.data.AuthenticatorTransport;
import com.webauthn4j.data.PublicKeyCredentialParameters;
import com.webauthn4j.data.PublicKeyCredentialType;
import com.webauthn4j.data.RegistrationData;
import com.webauthn4j.data.RegistrationParameters;
import com.webauthn4j.data.ResidentKeyRequirement;
import com.webauthn4j.data.UserVerificationRequirement;
import com.webauthn4j.data.attestation.authenticator.AAGUID;
import com.webauthn4j.data.attestation.authenticator.AttestedCredentialData;
import com.webauthn4j.data.attestation.authenticator.COSEKey;
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier;
import com.webauthn4j.data.client.Origin;
import com.webauthn4j.data.client.challenge.DefaultChallenge;
import com.webauthn4j.server.ServerProperty;
import com.webauthn4j.verifier.exception.MaliciousCounterValueException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * The WebAuthn relying party (library: webauthn4j): verifies registration and sign-in responses sent in the standard
 * {@code credential.toJSON()} form against a server challenge, this site's RP ID and its allowed origins.
 * Deliberately Spring-free (built by {@link PasskeyConfig}); every failure becomes a
 * {@link PasskeyVerificationException}.
 *
 * <p>Policy (docs/PASSKEYS_CONTRACT.md): user verification and user presence are required, attestation is "none"
 * (no attestation statement is trusted or needed), cross-origin (iframe) use is refused. The signature counter is
 * rejected only when it went backwards while both the stored and the presented values are non-zero (synced passkeys
 * always report 0).
 */
public class WebAuthnRelyingParty {

    /** WebAuthn credential type of every passkey. */
    public static final String CREDENTIAL_TYPE = PublicKeyCredentialType.PUBLIC_KEY.getValue();
    /** Passkeys are discoverable credentials (resident keys). */
    public static final String RESIDENT_KEY = ResidentKeyRequirement.REQUIRED.getValue();
    /** Fingerprint / face / device PIN on every registration and sign-in. */
    public static final String USER_VERIFICATION = UserVerificationRequirement.REQUIRED.getValue();
    /** No attestation statement is requested. */
    public static final String ATTESTATION = AttestationConveyancePreference.NONE.getValue();
    /** Signature algorithms offered at registration, most preferred first: ES256, EdDSA, RS256. */
    public static final List<COSEAlgorithmIdentifier> ALGORITHMS =
            List.of(COSEAlgorithmIdentifier.ES256, COSEAlgorithmIdentifier.EdDSA, COSEAlgorithmIdentifier.RS256);

    private final String rpId;
    private final Set<Origin> origins;
    private final ObjectConverter objectConverter = new ObjectConverter();
    private final WebAuthnManager manager;
    private final List<PublicKeyCredentialParameters> credentialParameters;

    /**
     * @param rpId    the relying-party ID: the site host (a registrable suffix of every origin's host)
     * @param origins allowed origins ({@code scheme://host[:port]}); at least one
     */
    public WebAuthnRelyingParty(String rpId, Collection<String> origins) {
        if (rpId == null || rpId.isBlank()) {
            throw new IllegalArgumentException("Passkeys need a relying-party ID");
        }
        this.rpId = rpId.trim().toLowerCase(Locale.ROOT);
        Set<Origin> parsed = new LinkedHashSet<>();
        if (origins != null) {
            for (String origin : origins) {
                if (origin != null && !origin.isBlank()) {
                    parsed.add(new Origin(originOf(origin)));
                }
            }
        }
        if (parsed.isEmpty()) {
            throw new IllegalArgumentException("Passkeys need at least one allowed origin");
        }
        this.origins = Set.copyOf(parsed);
        List<PublicKeyCredentialParameters> parameters = new ArrayList<>();
        for (COSEAlgorithmIdentifier alg : ALGORITHMS) {
            parameters.add(new PublicKeyCredentialParameters(PublicKeyCredentialType.PUBLIC_KEY, alg));
        }
        this.credentialParameters = List.copyOf(parameters);
        this.manager = WebAuthnManager.createNonStrictWebAuthnManager(objectConverter);
        // the library calls this only when the counter did not increase while one of both values is non-zero:
        // refuse unless the authenticator simply reports no counter (0)
        this.manager.getAuthenticationDataVerifier().setMaliciousCounterValueHandler(authenticationObject -> {
            long presented = authenticationObject.getAuthenticatorData().getSignCount();
            if (presented != 0) {
                throw new MaliciousCounterValueException("The signature counter went backwards",
                        authenticationObject.getCredentialRecord().getCounter(), presented);
            }
        });
    }

    public String rpId() {
        return rpId;
    }

    /** The allowed origins as text (for logs). */
    public List<String> origins() {
        return origins.stream().map(Origin::toString).sorted().toList();
    }

    /** COSE identifiers of {@link #ALGORITHMS} (pubKeyCredParams[].alg). */
    public static List<Long> algorithmIds() {
        return ALGORITHMS.stream().map(COSEAlgorithmIdentifier::getValue).toList();
    }

    // ------------------------------------------------------------------ registration

    /**
     * Verifies a registration response ({@code credential.toJSON()} of {@code navigator.credentials.create()}) made
     * for {@code challenge}; returns what must be stored.
     */
    public NewCredential verifyRegistration(String responseJson, byte[] challenge) {
        if (responseJson == null || challenge == null) {
            throw new PasskeyVerificationException("registration: missing response or challenge");
        }
        try {
            RegistrationData data = manager.parseRegistrationResponseJSON(responseJson);
            RegistrationParameters parameters =
                    new RegistrationParameters(serverProperty(challenge), credentialParameters, true, true);
            manager.verify(data, parameters);
            if (data.getAttestationObject() == null) {
                throw new PasskeyVerificationException("registration: no attestation object");
            }
            var authenticatorData = data.getAttestationObject().getAuthenticatorData();
            AttestedCredentialData attested = authenticatorData.getAttestedCredentialData();
            if (attested == null) {
                throw new PasskeyVerificationException("registration: no attested credential data");
            }
            byte[] publicKey = objectConverter.getCborMapper().writeValueAsBytes(attested.getCOSEKey());
            List<String> transports = data.getTransports() == null ? List.of()
                    : data.getTransports().stream().map(AuthenticatorTransport::getValue).sorted().toList();
            return new NewCredential(attested.getCredentialId(), publicKey, authenticatorData.getSignCount(),
                    attested.getAaguid().getValue(), transports);
        } catch (PasskeyVerificationException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new PasskeyVerificationException("registration: " + describe(e), e);
        }
    }

    // ------------------------------------------------------------------ sign-in

    /**
     * Reads a sign-in response ({@code credential.toJSON()} of {@code navigator.credentials.get()}) without
     * verifying it yet: the caller looks the credential up by {@link Assertion#credentialId()} first.
     */
    public Assertion readAssertion(String responseJson) {
        if (responseJson == null) {
            throw new PasskeyVerificationException("sign-in: missing response");
        }
        try {
            AuthenticationData data = manager.parseAuthenticationResponseJSON(responseJson);
            if (data.getCredentialId() == null || data.getCredentialId().length == 0) {
                throw new PasskeyVerificationException("sign-in: no credential id");
            }
            return new Assertion(data);
        } catch (PasskeyVerificationException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new PasskeyVerificationException("sign-in: unreadable response (" + describe(e) + ")", e);
        }
    }

    /**
     * Verifies {@code assertion} for {@code challenge} with the stored credential: client data type, challenge,
     * origin, RP ID hash, user presence + verification flags, signature and signature counter. Returns the counter
     * the authenticator presented.
     */
    public long verifyAssertion(Assertion assertion, byte[] challenge, StoredCredential stored) {
        if (assertion == null || challenge == null || stored == null) {
            throw new PasskeyVerificationException("sign-in: missing assertion, challenge or credential");
        }
        try {
            COSEKey coseKey = objectConverter.getCborMapper().readValue(stored.publicKeyCose(), COSEKey.class);
            AAGUID aaguid = stored.aaguid() == null ? AAGUID.NULL : new AAGUID(stored.aaguid());
            AttestedCredentialData attested = new AttestedCredentialData(aaguid, stored.credentialId(), coseKey);
            CredentialRecord record = new CredentialRecordImpl(null, null, null, null, stored.signCount(), attested,
                    null, null, null, null);
            AuthenticationParameters parameters =
                    new AuthenticationParameters(serverProperty(challenge), record, null, true, true);
            manager.verify(assertion.data, parameters);
            return assertion.data.getAuthenticatorData() == null ? 0
                    : assertion.data.getAuthenticatorData().getSignCount();
        } catch (PasskeyVerificationException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new PasskeyVerificationException("sign-in: " + describe(e), e);
        }
    }

    // ------------------------------------------------------------------ helpers

    private ServerProperty serverProperty(byte[] challenge) {
        return ServerProperty.builder()
                .origins(origins)
                .rpId(rpId)
                .challenge(new DefaultChallenge(challenge))
                .build();
    }

    private static String describe(RuntimeException e) {
        return e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
    }

    /** {@code https://saadatu-aldarein.com/path} → {@code saadatu-aldarein.com}; "" when it has no host. */
    public static String hostOf(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        try {
            String host = URI.create(url.trim()).getHost();
            return host == null ? "" : host.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    /**
     * {@code https://saadatu-aldarein.com/path} → {@code https://saadatu-aldarein.com} (scheme, host and an explicit
     * port only).
     */
    public static String originOf(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("Empty origin");
        }
        URI uri = URI.create(url.trim());
        if (uri.getScheme() == null || uri.getHost() == null) {
            throw new IllegalArgumentException("Not an origin: " + url);
        }
        String origin = uri.getScheme().toLowerCase(Locale.ROOT) + "://" + uri.getHost().toLowerCase(Locale.ROOT);
        return uri.getPort() == -1 ? origin : origin + ":" + uri.getPort();
    }

    // ------------------------------------------------------------------ types

    /**
     * A verified new passkey: credential ID, COSE public key, initial signature counter, authenticator model (AAGUID,
     * all zeros for most synced passkeys) and the transports the browser reported.
     */
    public record NewCredential(byte[] credentialId, byte[] publicKeyCose, long signCount, UUID aaguid,
                                List<String> transports) {
    }

    /** A stored passkey as the verification needs it. */
    public record StoredCredential(byte[] credentialId, byte[] publicKeyCose, long signCount, UUID aaguid) {
    }

    /** A parsed (not yet verified) sign-in response. */
    public static final class Assertion {

        private final AuthenticationData data;

        private Assertion(AuthenticationData data) {
            this.data = data;
        }

        /** {@code rawId}: the passkey that signed. */
        public byte[] credentialId() {
            return data.getCredentialId();
        }

        /** The user handle stored in the passkey at registration (null when the authenticator sent none). */
        public byte[] userHandle() {
            return data.getUserHandle();
        }
    }
}
