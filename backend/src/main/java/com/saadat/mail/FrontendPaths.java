package com.saadat.mail;

/**
 * SPA routes referenced from e-mails, push payloads, notification and insight links (relative; prefix with
 * {@code app.frontend-url} for absolute URLs). Source: {@code frontend/src/app/router.tsx} and
 * docs/ANALYTICS_REPORTS_CONTRACT.md (insight links).
 */
public final class FrontendPaths {

    public static final String HOME = "/";
    public static final String MAGIC_CALLBACK = "/auth/callback";
    public static final String MAGIC_CALLBACK_TOKEN_PARAM = "token";
    public static final String ME = "/me";
    public static final String MY_DREAMS = "/me/dreams";
    public static final String MY_PAYMENTS = "/me/payments";
    public static final String NEW_DREAM = "/me/new";
    public static final String PACKAGES = "/me/packages";

    /** Prefix of every interpreter route (page views under it are never tracked). */
    public static final String ADMIN_ROOT = "/admin";
    public static final String ADMIN_DREAMS = "/admin/dreams";
    public static final String ADMIN_USERS = "/admin/users";
    /** The interpreter's queue as named by the analytics contract (insight links). */
    public static final String ADMIN_QUEUE = "/admin/queue";
    public static final String ADMIN_WAIT_TIME = "/admin/wait-time";
    public static final String ADMIN_TESTIMONIALS = "/admin/testimonials";
    public static final String ADMIN_PRICING = "/admin/pricing";

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

    /** {@code /admin/users/{id}} */
    public static String adminUser(Object userId) {
        return ADMIN_USERS + "/" + userId;
    }
}
