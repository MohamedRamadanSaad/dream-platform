package com.saadat.auth.service;

/**
 * Verifies a Google ID token (the {@code credential} from Google Identity Services).
 * Implementations throw {@link com.saadat.common.error.UnauthorizedException} for invalid tokens and
 * {@link com.saadat.common.error.NotConfiguredException} when Google login is not configured.
 */
public interface GoogleTokenVerifier {

    GoogleIdentity verify(String idToken);

    /** The verified claims we use. {@code subject} is Google's stable user id ("sub"). */
    record GoogleIdentity(String subject, String email, boolean emailVerified, String name) {
        @Override
        public String toString() {
            return "GoogleIdentity[subject=" + subject + "]";
        }
    }
}
