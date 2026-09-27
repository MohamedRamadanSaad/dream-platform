package com.saadat.common.web;

/** Helpers to keep PII and secrets out of logs (spec §0: never log tokens/emails in full). */
public final class LogMask {

    private LogMask() {
    }

    /** "fatema@example.com" → "f***@example.com". */
    public static String email(String email) {
        if (email == null || email.isBlank()) {
            return "";
        }
        int at = email.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        return email.charAt(0) + "***" + email.substring(at);
    }

    /** Shows only the first 6 characters of a token. */
    public static String token(String token) {
        if (token == null || token.isEmpty()) {
            return "";
        }
        return token.length() <= 6 ? "***" : token.substring(0, 6) + "***";
    }
}
