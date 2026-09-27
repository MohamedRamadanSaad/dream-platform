package com.saadat.auth.service;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.saadat.common.error.NotConfiguredException;
import com.saadat.common.error.UnauthorizedException;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Collections;
import lombok.extern.slf4j.Slf4j;

/**
 * Real verifier backed by google-api-client's {@link GoogleIdTokenVerifier}: checks signature (Google's
 * rotating certs, cached), issuer, expiry and audience = {@code app.google-client-id}.
 */
@Slf4j
public class GoogleApiTokenVerifier implements GoogleTokenVerifier {

    private static final String CLAIM_NAME = "name";

    private final String clientId;
    private final GoogleIdTokenVerifier verifier;

    public GoogleApiTokenVerifier(String clientId) {
        this.clientId = clientId == null ? "" : clientId.trim();
        this.verifier = this.clientId.isEmpty() ? null
                : new GoogleIdTokenVerifier.Builder(new NetHttpTransport(), GsonFactory.getDefaultInstance())
                        .setAudience(Collections.singletonList(this.clientId))
                        .build();
    }

    @Override
    public GoogleIdentity verify(String idToken) {
        if (verifier == null) {
            throw new NotConfiguredException("Google login is not configured");
        }
        if (idToken == null || idToken.isBlank()) {
            throw new UnauthorizedException("Invalid Google token");
        }
        final GoogleIdToken token;
        try {
            token = verifier.verify(idToken);
        } catch (GeneralSecurityException | IOException | IllegalArgumentException e) {
            log.info("Google ID token verification failed: {}", e.getClass().getSimpleName());
            throw new UnauthorizedException("Invalid Google token");
        }
        if (token == null) {
            throw new UnauthorizedException("Invalid Google token");
        }
        GoogleIdToken.Payload payload = token.getPayload();
        Object name = payload.get(CLAIM_NAME);
        return new GoogleIdentity(
                payload.getSubject(),
                payload.getEmail(),
                Boolean.TRUE.equals(payload.getEmailVerified()),
                name instanceof String s ? s : "");
    }
}
