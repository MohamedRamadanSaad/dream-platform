package com.saadat.passkeys.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.saadat.passkeys.TestPasskeyDevice;
import com.saadat.passkeys.api.PasskeyDtos.AuthenticatorSelection;
import com.saadat.passkeys.api.PasskeyDtos.CreationOptions;
import com.saadat.passkeys.api.PasskeyDtos.CredentialParameters;
import com.saadat.passkeys.api.PasskeyDtos.RelyingParty;
import com.saadat.passkeys.api.PasskeyDtos.RequestOptions;
import com.saadat.passkeys.api.PasskeyDtos.UserEntity;
import com.saadat.passkeys.service.WebAuthnRelyingParty.Assertion;
import com.saadat.passkeys.service.WebAuthnRelyingParty.NewCredential;
import com.saadat.passkeys.service.WebAuthnRelyingParty.StoredCredential;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The WebAuthn checks with real keys and signatures (webauthn4j-test emulator), without Spring: a registration and
 * sign-in round trip, and every way a response must be refused.
 */
class WebAuthnRelyingPartyTest {

    private static final String RP_ID = "saadatu-aldarein.com";
    private static final String ORIGIN = "https://saadatu-aldarein.com";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final WebAuthnRelyingParty relyingParty = new WebAuthnRelyingParty(RP_ID, List.of(ORIGIN));

    @Test
    void derivesTheRpIdAndTheOriginFromTheSiteUrl() {
        assertThat(WebAuthnRelyingParty.hostOf("https://Saadatu-Aldarein.com/")).isEqualTo("saadatu-aldarein.com");
        assertThat(WebAuthnRelyingParty.hostOf("http://localhost:5173")).isEqualTo("localhost");
        assertThat(WebAuthnRelyingParty.hostOf("")).isEmpty();
        assertThat(WebAuthnRelyingParty.originOf("https://saadatu-aldarein.com/me/profile"))
                .isEqualTo("https://saadatu-aldarein.com");
        assertThat(WebAuthnRelyingParty.originOf("http://localhost:5173/")).isEqualTo("http://localhost:5173");
        assertThat(new WebAuthnRelyingParty("Localhost", List.of("http://localhost:5173/", " ")).origins())
                .containsExactly("http://localhost:5173");
        assertThatThrownBy(() -> new WebAuthnRelyingParty(" ", List.of(ORIGIN)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WebAuthnRelyingParty(RP_ID, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void registersAPasskeyAndVerifiesItsSignIns() throws Exception {
        UUID user = UUID.randomUUID();
        TestPasskeyDevice phone = new TestPasskeyDevice(ORIGIN);
        byte[] challenge = random();

        Map<String, Object> created = phone.create(creationOptions(challenge, user));
        NewCredential credential = relyingParty.verifyRegistration(JSON.writeValueAsString(created), challenge);

        assertThat(TestPasskeyDevice.base64url(credential.credentialId())).isEqualTo(created.get("rawId"));
        assertThat(credential.publicKeyCose()).isNotEmpty();
        assertThat(credential.signCount()).isEqualTo(1);
        assertThat(credential.aaguid()).isEqualTo(new UUID(0, 0));
        assertThat(credential.transports()).containsExactly("hybrid", "internal");

        byte[] signInChallenge = random();
        Assertion assertion = relyingParty.readAssertion(JSON.writeValueAsString(phone.get(requestOptions(signInChallenge))));
        assertThat(assertion.credentialId()).isEqualTo(credential.credentialId());
        assertThat(assertion.userHandle()).isEqualTo(PasskeyService.userHandle(user));
        assertThat(relyingParty.verifyAssertion(assertion, signInChallenge, stored(credential, 1))).isEqualTo(2);
    }

    @Test
    void refusesAnotherChallengeOriginOrRelyingParty() throws Exception {
        TestPasskeyDevice phone = new TestPasskeyDevice(ORIGIN);
        byte[] challenge = random();
        String created = JSON.writeValueAsString(phone.create(creationOptions(challenge, UUID.randomUUID())));
        assertThatThrownBy(() -> relyingParty.verifyRegistration(created, random()))
                .isInstanceOf(PasskeyVerificationException.class);
        NewCredential credential = relyingParty.verifyRegistration(created, challenge);

        byte[] signInChallenge = random();
        String signedIn = JSON.writeValueAsString(phone.get(requestOptions(signInChallenge)));
        StoredCredential stored = stored(credential, credential.signCount());
        assertThatThrownBy(() -> relyingParty.verifyAssertion(relyingParty.readAssertion(signedIn), random(), stored))
                .isInstanceOf(PasskeyVerificationException.class)
                .hasMessageContaining("Challenge");
        WebAuthnRelyingParty otherSite = new WebAuthnRelyingParty(RP_ID, List.of("https://saadatu-aldarein.example.com"));
        assertThatThrownBy(() -> otherSite.verifyAssertion(otherSite.readAssertion(signedIn), signInChallenge, stored))
                .isInstanceOf(PasskeyVerificationException.class)
                .hasMessageContaining("Origin");
        WebAuthnRelyingParty otherRp = new WebAuthnRelyingParty("example.com", List.of(ORIGIN));
        assertThatThrownBy(() -> otherRp.verifyAssertion(otherRp.readAssertion(signedIn), signInChallenge, stored))
                .isInstanceOf(PasskeyVerificationException.class)
                .hasMessageContaining("RpId");

        // registered on a phishing copy of the site
        TestPasskeyDevice phished = new TestPasskeyDevice("https://saadatu-aldarein.example.com");
        byte[] phishedChallenge = random();
        String phishedCreated = JSON.writeValueAsString(phished.create(creationOptions(phishedChallenge, UUID.randomUUID())));
        assertThatThrownBy(() -> relyingParty.verifyRegistration(phishedCreated, phishedChallenge))
                .isInstanceOf(PasskeyVerificationException.class)
                .hasMessageContaining("Origin");
    }

    @Test
    void refusesATamperedSignature() throws Exception {
        TestPasskeyDevice phone = new TestPasskeyDevice(ORIGIN);
        byte[] challenge = random();
        NewCredential credential = relyingParty.verifyRegistration(
                JSON.writeValueAsString(phone.create(creationOptions(challenge, UUID.randomUUID()))), challenge);

        byte[] signInChallenge = random();
        ObjectNode signedIn = JSON.valueToTree(phone.get(requestOptions(signInChallenge)));
        ObjectNode response = (ObjectNode) signedIn.get("response");
        byte[] signature = Base64.getUrlDecoder().decode(response.get("signature").asText());
        signature[signature.length - 1] ^= 0x01;
        response.put("signature", TestPasskeyDevice.base64url(signature));

        assertThatThrownBy(() -> relyingParty.verifyAssertion(
                relyingParty.readAssertion(signedIn.toString()), signInChallenge, stored(credential, 1)))
                .isInstanceOf(PasskeyVerificationException.class)
                .hasMessageContaining("Signature");
    }

    @Test
    void refusesACounterThatDidNotGrowButAcceptsPasskeysWithoutCounter() throws Exception {
        TestPasskeyDevice key = new TestPasskeyDevice(ORIGIN);
        byte[] challenge = random();
        NewCredential credential = relyingParty.verifyRegistration(
                JSON.writeValueAsString(key.create(creationOptions(challenge, UUID.randomUUID()))), challenge);

        byte[] first = random();
        String firstSignIn = JSON.writeValueAsString(key.get(requestOptions(first)));
        long counter = relyingParty.verifyAssertion(relyingParty.readAssertion(firstSignIn), first,
                stored(credential, credential.signCount()));
        assertThat(counter).isEqualTo(2);
        // the same counter value again (a cloned key, or a replayed response): both values non-zero, not growing
        key.freezeCounter();
        byte[] second = random();
        String secondSignIn = JSON.writeValueAsString(key.get(requestOptions(second)));
        assertThatThrownBy(() -> relyingParty.verifyAssertion(relyingParty.readAssertion(secondSignIn), second,
                stored(credential, counter)))
                .isInstanceOf(PasskeyVerificationException.class)
                .hasMessageContaining("counter");

        // synced passkeys always report 0: accepted, also against a stored non-zero value
        TestPasskeyDevice synced = TestPasskeyDevice.withoutCounter(ORIGIN);
        byte[] syncedChallenge = random();
        NewCredential syncedCredential = relyingParty.verifyRegistration(
                JSON.writeValueAsString(synced.create(creationOptions(syncedChallenge, UUID.randomUUID()))),
                syncedChallenge);
        assertThat(syncedCredential.signCount()).isZero();
        for (long storedCounter : new long[] {0, 0, 5}) {
            byte[] c = random();
            String signIn = JSON.writeValueAsString(synced.get(requestOptions(c)));
            assertThat(relyingParty.verifyAssertion(relyingParty.readAssertion(signIn), c,
                    stored(syncedCredential, storedCounter))).isZero();
        }
    }

    @Test
    void refusesUnreadableResponses() {
        byte[] challenge = random();
        for (String bad : new String[] {"nope", "[]", "{}", "{\"id\":\"abc\",\"type\":\"public-key\",\"response\":{}}"}) {
            assertThatThrownBy(() -> relyingParty.verifyRegistration(bad, challenge))
                    .as(bad).isInstanceOf(PasskeyVerificationException.class);
            assertThatThrownBy(() -> relyingParty.readAssertion(bad))
                    .as(bad).isInstanceOf(PasskeyVerificationException.class);
        }
        assertThatThrownBy(() -> relyingParty.verifyRegistration(null, challenge))
                .isInstanceOf(PasskeyVerificationException.class);
    }

    // ------------------------------------------------------------------ helpers

    private static byte[] random() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    private static StoredCredential stored(NewCredential credential, long signCount) {
        return new StoredCredential(credential.credentialId(), credential.publicKeyCose(), signCount,
                credential.aaguid());
    }

    /** options.publicKey as PasskeyService builds it. */
    private static String creationOptions(byte[] challenge, UUID user) throws Exception {
        List<CredentialParameters> parameters = WebAuthnRelyingParty.algorithmIds().stream()
                .map(alg -> new CredentialParameters(WebAuthnRelyingParty.CREDENTIAL_TYPE, alg))
                .toList();
        CreationOptions options = new CreationOptions(
                new RelyingParty(RP_ID, "Saadat Al-Darain"),
                new UserEntity(TestPasskeyDevice.base64url(PasskeyService.userHandle(user)), "ahmed@example.com", "Ahmed"),
                TestPasskeyDevice.base64url(challenge),
                parameters,
                300_000L,
                List.of(),
                new AuthenticatorSelection(WebAuthnRelyingParty.RESIDENT_KEY, true, WebAuthnRelyingParty.USER_VERIFICATION),
                WebAuthnRelyingParty.ATTESTATION);
        return JSON.writeValueAsString(options);
    }

    private static String requestOptions(byte[] challenge) throws Exception {
        return JSON.writeValueAsString(new RequestOptions(TestPasskeyDevice.base64url(challenge), 300_000L, RP_ID,
                List.of(), WebAuthnRelyingParty.USER_VERIFICATION));
    }
}
