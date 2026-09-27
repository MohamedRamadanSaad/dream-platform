package com.saadat.auth.service;

import com.saadat.common.error.UnauthorizedException;

/**
 * Local/test only (installed when {@code app.auth.allow-mock=true}): the literal idToken {@code "mock"} logs in
 * as {@code app.auth.mock-email}; any other token goes to the real verifier.
 */
public class MockGoogleTokenVerifier implements GoogleTokenVerifier {

    public static final String MOCK_TOKEN = "mock";
    private static final String SUBJECT_PREFIX = "mock-";

    private final GoogleTokenVerifier delegate;
    private final String mockEmail;

    public MockGoogleTokenVerifier(GoogleTokenVerifier delegate, String mockEmail) {
        this.delegate = delegate;
        this.mockEmail = mockEmail == null ? "" : mockEmail.trim();
    }

    @Override
    public GoogleIdentity verify(String idToken) {
        if (MOCK_TOKEN.equals(idToken)) {
            if (mockEmail.isEmpty()) {
                throw new UnauthorizedException("Mock login is not configured (app.auth.mock-email)");
            }
            return new GoogleIdentity(SUBJECT_PREFIX + MagicLinkService.normalizeEmail(mockEmail), mockEmail, true, "");
        }
        return delegate.verify(idToken);
    }
}
