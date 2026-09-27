package com.saadat.mail;

/**
 * SPA routes referenced from e-mails, push payloads and notification links (relative; prefix with
 * {@code app.frontend-url} for absolute URLs). Source: {@code frontend/src/app/router.tsx}.
 */
public final class FrontendPaths {

    public static final String MAGIC_CALLBACK = "/auth/callback";
    public static final String MAGIC_CALLBACK_TOKEN_PARAM = "token";
    public static final String ME = "/me";
    public static final String MY_DREAMS = "/me/dreams";
    public static final String MY_PAYMENTS = "/me/payments";
    public static final String ADMIN_DREAMS = "/admin/dreams";

    private FrontendPaths() {
    }

    /** {@code /me/dreams/{id}} */
    public static String myDream(Object dreamId) {
        return MY_DREAMS + "/" + dreamId;
    }

    /** {@code /admin/dreams/{id}} */
    public static String adminDream(Object dreamId) {
        return ADMIN_DREAMS + "/" + dreamId;
    }
}
