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
    public static final String ME_PROFILE = "/me/profile";
    /** Anchor of the devices section of both profile pages (user and interpreter). */
    public static final String DEVICES_ANCHOR = "#devices";
    /** The user's devices list (new sign-in e-mail). */
    public static final String MY_DEVICES = ME_PROFILE + DEVICES_ANCHOR;
    /** Anchor of the passkeys section of both profile pages (user and interpreter). */
    public static final String PASSKEYS_ANCHOR = "#passkeys";
    /** The user's passkeys (passkey-added e-mail). */
    public static final String MY_PASSKEYS = ME_PROFILE + PASSKEYS_ANCHOR;

    /** Prefix of every interpreter route (page views under it are never tracked). */
    public static final String ADMIN_ROOT = "/admin";
    /** The interpreter's own profile page (personal data, language/theme, devices, sign-out). */
    public static final String ADMIN_PROFILE = "/admin/profile";
    /** The interpreter's devices list (new sign-in e-mail). */
    public static final String ADMIN_DEVICES = ADMIN_PROFILE + DEVICES_ANCHOR;
    /** The interpreter's passkeys (passkey-added e-mail). */
    public static final String ADMIN_PASSKEYS = ADMIN_PROFILE + PASSKEYS_ANCHOR;
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

    /** The devices list of the account's own profile page: {@code /admin/profile#devices} or {@code /me/profile#devices}. */
    public static String devices(boolean interpreter) {
        return interpreter ? ADMIN_DEVICES : MY_DEVICES;
    }

    /** The passkeys of the account's own profile page: {@code /admin/profile#passkeys} or {@code /me/profile#passkeys}. */
    public static String passkeys(boolean interpreter) {
        return interpreter ? ADMIN_PASSKEYS : MY_PASSKEYS;
    }
}
