package com.saadat.passkeys.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Request/response records of the passkey routes (docs/PASSKEYS_CONTRACT.md). The {@code publicKey} options are the
 * standard WebAuthn JSON forms (binary fields base64url without padding) so the browser can pass them to
 * {@code PublicKeyCredential.parseCreationOptionsFromJSON} / {@code parseRequestOptionsFromJSON}; absent optional
 * members are left out (never {@code null}) as WebAuthn requires.
 */
public final class PasskeyDtos {

    /** Longest passkey label (column passkeys.label). */
    public static final int LABEL_MAX = 100;

    private PasskeyDtos() {
    }

    // ------------------------------------------------------------------ the caller's passkeys

    /** types.ts {@code PasskeyDto}: {@code lastUsedAt} is null until the first sign-in with it. */
    public record PasskeyDto(UUID id, String label, Instant createdAt, Instant lastUsedAt) {
    }

    /**
     * Body of POST /me/passkeys/registration: the {@code requestId} of the options, the new credential in
     * {@code credential.toJSON()} form and an optional label (default "&lt;browser&gt; · &lt;os&gt;").
     */
    public record PasskeyRegistrationRequest(String requestId, JsonNode credential, @Size(max = LABEL_MAX) String label) {
    }

    /** Body of POST /auth/passkey/verify; {@code rememberMe} as in the other sign-ins (missing = true). */
    public record PasskeySignInRequest(String requestId, JsonNode credential, Boolean rememberMe) {
    }

    // ------------------------------------------------------------------ registration options

    /** Response of POST /me/passkeys/registration/options. */
    public record RegistrationOptionsResponse(String requestId, CreationOptions publicKey) {
    }

    /** {@code PublicKeyCredentialCreationOptionsJSON}. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CreationOptions(
            RelyingParty rp,
            UserEntity user,
            String challenge,
            List<CredentialParameters> pubKeyCredParams,
            Long timeout,
            List<CredentialDescriptor> excludeCredentials,
            AuthenticatorSelection authenticatorSelection,
            String attestation) {
    }

    /** {@code PublicKeyCredentialRpEntity}: the site host and the brand name. */
    public record RelyingParty(String id, String name) {
    }

    /** {@code PublicKeyCredentialUserEntityJSON}: {@code id} = base64url user handle. */
    public record UserEntity(String id, String name, String displayName) {
    }

    /** {@code PublicKeyCredentialParameters}. */
    public record CredentialParameters(String type, long alg) {
    }

    /** {@code PublicKeyCredentialDescriptorJSON}: {@code id} = base64url credential ID. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CredentialDescriptor(String type, String id, List<String> transports) {
    }

    /** {@code AuthenticatorSelectionCriteria}. */
    public record AuthenticatorSelection(String residentKey, boolean requireResidentKey, String userVerification) {
    }

    // ------------------------------------------------------------------ sign-in options

    /** Response of POST /auth/passkey/options. */
    public record SignInOptionsResponse(String requestId, RequestOptions publicKey) {
    }

    /** {@code PublicKeyCredentialRequestOptionsJSON}; {@code allowCredentials} stays empty (discoverable passkeys). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RequestOptions(
            String challenge,
            Long timeout,
            String rpId,
            List<CredentialDescriptor> allowCredentials,
            String userVerification) {
    }
}
