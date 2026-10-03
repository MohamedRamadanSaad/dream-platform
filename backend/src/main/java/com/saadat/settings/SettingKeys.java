package com.saadat.settings;

import java.util.List;

/**
 * Keys of the {@code app_settings} table (seeded in V1__settings.sql). Read them through
 * {@link SettingsService}; never hard-code the values they hold.
 */
public final class SettingKeys {

    private SettingKeys() {
    }

    // ---- brand ----
    public static final String BRAND_NAME_AR = "brand.name.ar";
    public static final String BRAND_NAME_EN = "brand.name.en";
    public static final String BRAND_TAGLINE_AR = "brand.tagline.ar";
    public static final String BRAND_TAGLINE_EN = "brand.tagline.en";
    public static final String BRAND_SUPPORT_EMAIL = "brand.support_email";
    /** Seller details required for online sales (Egyptian consumer protection law 181/2018): shown on the terms
     *  page, in the footer and on the payment receipt. Empty values are simply not shown. */
    public static final String BRAND_LEGAL_NAME = "brand.legal_name";
    public static final String BRAND_TAX_REGISTRATION_NO = "brand.tax_registration_no";
    public static final String BRAND_YOUTUBE_URL = "brand.youtube_url";
    /** Empty until provided; the YouTube poller skips when empty. */
    public static final String BRAND_YOUTUBE_CHANNEL_ID = "brand.youtube_channel_id";

    // ---- wait time (admin/wait-time maps these to WaitTimeSettings) ----
    public static final String WAIT_BUSY = "wait.busy";
    public static final String WAIT_NORMAL_HOURS = "wait.normal_hours";
    public static final String WAIT_BUSY_MIN_DAYS = "wait.busy_min_days";
    public static final String WAIT_BUSY_MAX_DAYS = "wait.busy_max_days";
    public static final String WAIT_MESSAGE_AR = "wait.message_ar";
    public static final String WAIT_MESSAGE_EN = "wait.message_en";
    /** ISO-8601 instant or null. */
    public static final String WAIT_AUTO_RESET_AT = "wait.auto_reset_at";

    // ---- dreams ----
    public static final String DREAMS_MIN_CHARS = "dreams.min_chars";
    public static final String DREAMS_MAX_CHARS = "dreams.max_chars";
    public static final String DREAMS_DRAFT_LIMIT = "dreams.draft_limit";
    public static final String DREAMS_REPLY_REMINDER_HOURS = "dreams.reply_reminder_hours";
    public static final String DREAMS_TESTIMONIAL_REQUEST_DAYS = "dreams.testimonial_request_days";

    // ---- auth ----
    public static final String AUTH_MAGIC_TTL_MINUTES = "auth.magic_ttl_minutes";
    public static final String AUTH_ACCESS_TTL_MINUTES = "auth.access_ttl_minutes";
    /** Lifetime of a "remember me" sign-in (persistent cookie), renewed on every refresh. */
    public static final String AUTH_REFRESH_TTL_DAYS = "auth.refresh_ttl_days";
    /** Lifetime of a sign-in without "remember me" (browser-session cookie), renewed on every refresh (V18). */
    public static final String AUTH_SESSION_TTL_HOURS = "auth.session_ttl_hours";
    /** New sign-in e-mail: a browser + system used to sign in during the last N days is a known device (V18). */
    public static final String AUTH_KNOWN_DEVICE_DAYS = "auth.known_device_days";
    /** Passkeys: a registration or sign-in challenge is valid for N seconds and usable once (V20). */
    public static final String AUTH_PASSKEY_CHALLENGE_TTL_SECONDS = "auth.passkey_challenge_ttl_seconds";

    // ---- orders ----
    public static final String ORDERS_EXPIRE_MINUTES = "orders.expire_minutes";
    /** The user sees their next credit expiry when it is within N days. */
    public static final String CREDITS_EXPIRY_NOTICE_DAYS = "credits.expiry_notice_days";

    // ---- interpreter ----
    /** Comma-separated list of e-mails that get role INTERPRETER on first login. */
    public static final String INTERPRETER_EMAILS = "interpreter.emails";
    /** E-mails ending with @domain are interpreter accounts (magic-link only). Empty disables the rule. */
    public static final String INTERPRETER_EMAIL_DOMAIN = "interpreter.email_domain";
    public static final String INTERPRETER_DIGEST_HOUR = "interpreter.digest_hour";

    // ---- pricing ----
    public static final String PRICING_GLOBAL_CURRENCY = "pricing.global_currency";
    /** Fallback country when no CF-IPCountry header is present. */
    public static final String PRICING_DEFAULT_COUNTRY = "pricing.default_country";
    /** JSON map currency → USD rate, e.g. {"EGP":0.0208,"SAR":0.2667,"USD":1}. */
    public static final String PRICING_FX_TO_USD = "pricing.fx_to_usd";

    // ---- youtube ----
    public static final String YOUTUBE_POLL_MINUTES = "youtube.poll_minutes";

    // ---- public stats (display strings) ----
    public static final String STATS_SUBSCRIBERS = "stats.subscribers";
    public static final String STATS_VIEWS = "stats.views";
    public static final String STATS_VIDEOS = "stats.videos";
    /** Historical interpreted-dreams count added to the live INTERPRETED count for the public counter. */
    public static final String STATS_INTERPRETED_BASE = "stats.interpreted_base";

    // ---- scheduling ----
    /** IANA zone used for daily schedules (digest) and as the business time zone of analytics/reports. */
    public static final String SCHEDULE_TIME_ZONE = "schedule.time_zone";

    // ---- e-mail events: BOOL mail.event.<template> (V14); missing or true = send. magic-link has no switch ----
    public static final String MAIL_EVENT_PREFIX = "mail.event.";
    public static final String MAIL_EVENT_WELCOME = "mail.event.welcome";
    public static final String MAIL_EVENT_PAYMENT_FAILED = "mail.event.payment-failed";
    public static final String MAIL_EVENT_DREAM_CANCELLED = "mail.event.dream-cancelled";
    public static final String MAIL_EVENT_CREDITS_ADJUSTED = "mail.event.credits-adjusted";
    public static final String MAIL_EVENT_TESTIMONIAL_APPROVED = "mail.event.testimonial-approved";
    public static final String MAIL_EVENT_ACCOUNT_DELETED = "mail.event.account-deleted";
    public static final String MAIL_EVENT_NEW_USER = "mail.event.new-user";
    public static final String MAIL_EVENT_TESTIMONIAL_RECEIVED = "mail.event.testimonial-received";
    public static final String MAIL_EVENT_DREAM_SUBMITTED = "mail.event.dream-submitted";
    public static final String MAIL_EVENT_DREAM_RECEIVED = "mail.event.dream-received";
    public static final String MAIL_EVENT_INTERPRETER_QUESTION = "mail.event.interpreter-question";
    public static final String MAIL_EVENT_USER_REPLIED = "mail.event.user-replied";
    public static final String MAIL_EVENT_INTERPRETATION_READY = "mail.event.interpretation-ready";
    public static final String MAIL_EVENT_PAYMENT_RECEIPT = "mail.event.payment-receipt";
    public static final String MAIL_EVENT_PAYMENT_SUSPICIOUS = "mail.event.payment-suspicious";
    public static final String MAIL_EVENT_REPLY_REMINDER = "mail.event.reply-reminder";
    public static final String MAIL_EVENT_TESTIMONIAL_REQUEST = "mail.event.testimonial-request";
    public static final String MAIL_EVENT_INTERPRETER_DIGEST = "mail.event.interpreter-digest";
    public static final String MAIL_EVENT_YOUTUBE_NEW_VIDEO = "mail.event.youtube-new-video";
    /** Automatic reply to e-mails received by the support mailbox (V16). */
    public static final String MAIL_EVENT_SUPPORT_AUTO_REPLY = "mail.event.support-auto-reply";
    /** Security alert to the user after a sign-in from a new device (V18). */
    public static final String MAIL_EVENT_NEW_SIGN_IN = "mail.event.new-sign-in";
    /** Security notice to the account owner after a passkey was added (V20). */
    public static final String MAIL_EVENT_PASSKEY_ADDED = "mail.event.passkey-added";
    /** Support ticket moved to "in progress": e-mail to the sender with the interpreter's message (V21). */
    public static final String MAIL_EVENT_SUPPORT_IN_PROGRESS = "mail.event.support-in-progress";
    /** Support ticket closed: e-mail to the sender with the interpreter's message (V21). */
    public static final String MAIL_EVENT_SUPPORT_CLOSED = "mail.event.support-closed";

    /** Every e-mail event switch (one per template except magic-link). */
    public static final List<String> MAIL_EVENTS = List.of(
            MAIL_EVENT_WELCOME, MAIL_EVENT_PAYMENT_FAILED, MAIL_EVENT_DREAM_CANCELLED, MAIL_EVENT_CREDITS_ADJUSTED,
            MAIL_EVENT_TESTIMONIAL_APPROVED, MAIL_EVENT_ACCOUNT_DELETED, MAIL_EVENT_NEW_USER,
            MAIL_EVENT_TESTIMONIAL_RECEIVED, MAIL_EVENT_DREAM_SUBMITTED, MAIL_EVENT_DREAM_RECEIVED,
            MAIL_EVENT_INTERPRETER_QUESTION, MAIL_EVENT_USER_REPLIED, MAIL_EVENT_INTERPRETATION_READY,
            MAIL_EVENT_PAYMENT_RECEIPT, MAIL_EVENT_PAYMENT_SUSPICIOUS, MAIL_EVENT_REPLY_REMINDER,
            MAIL_EVENT_TESTIMONIAL_REQUEST, MAIL_EVENT_INTERPRETER_DIGEST, MAIL_EVENT_YOUTUBE_NEW_VIDEO,
            MAIL_EVENT_SUPPORT_AUTO_REPLY, MAIL_EVENT_NEW_SIGN_IN,
            MAIL_EVENT_PASSKEY_ADDED, MAIL_EVENT_SUPPORT_IN_PROGRESS, MAIL_EVENT_SUPPORT_CLOSED);

    /** The switch of an e-mail template: {@code mail.event.<template>}. */
    public static String mailEvent(String template) {
        return MAIL_EVENT_PREFIX + template;
    }

    // ---- e-mail themes (V16): STRING mail.theme.<template>; blank/unknown = mail.theme.default ----
    public static final String MAIL_THEME_PREFIX = "mail.theme.";
    /** Theme of every e-mail whose own mail.theme.<template> is blank (a key of mail/themes.json). */
    public static final String MAIL_THEME_DEFAULT = "mail.theme.default";
    /** Base URL of the e-mail theme images; blank = app.frontend-url. */
    public static final String MAIL_ASSETS_BASE_URL = "mail.assets_base_url";
    /** The support mailbox auto-reply is sent to the same address at most once per N hours. */
    public static final String MAIL_AUTO_REPLY_COOLDOWN_HOURS = "mail.auto_reply_cooldown_hours";
    /** Keep a copy of every sent e-mail in the mailbox's Sent folder (IMAP), so the mailbox shows what was sent. */
    public static final String MAIL_SAVE_TO_SENT = "mail.save_to_sent";
    /** IMAP folder that receives the copies (Hostinger: INBOX.Sent). */
    public static final String MAIL_SENT_FOLDER = "mail.sent_folder";

    /** The theme of an e-mail template: {@code mail.theme.<template>}. */
    public static String mailTheme(String template) {
        return MAIL_THEME_PREFIX + template;
    }

    // ---- insights (GET /admin/analytics/insights) ----
    /** Dreams in AWAITING_USER_REPLY for longer than N days are reported. */
    public static final String INSIGHTS_AWAITING_REPLY_DAYS = "insights.awaiting_reply_days";
    /** Visits up/down by at least N percent vs the same days of last month are reported. */
    public static final String INSIGHTS_TRAFFIC_CHANGE_PERCENT = "insights.traffic_change_percent";
    /** An interpretation streak of at least N consecutive days is celebrated. */
    public static final String INSIGHTS_STREAK_MIN_DAYS = "insights.streak_min_days";

    // ---- reports ----
    /** Row cap of GET /admin/dreams/export. */
    public static final String REPORTS_EXCEL_MAX_ROWS = "reports.excel_max_rows";

    /** Every key seeded by the migrations. */
    public static final List<String> ALL = List.of(
            BRAND_NAME_AR, BRAND_NAME_EN, BRAND_TAGLINE_AR, BRAND_TAGLINE_EN, BRAND_SUPPORT_EMAIL,
            BRAND_YOUTUBE_URL, BRAND_YOUTUBE_CHANNEL_ID,
            BRAND_LEGAL_NAME, BRAND_TAX_REGISTRATION_NO,
            WAIT_BUSY, WAIT_NORMAL_HOURS, WAIT_BUSY_MIN_DAYS, WAIT_BUSY_MAX_DAYS, WAIT_MESSAGE_AR, WAIT_MESSAGE_EN,
            WAIT_AUTO_RESET_AT,
            DREAMS_MIN_CHARS, DREAMS_MAX_CHARS, DREAMS_DRAFT_LIMIT, DREAMS_REPLY_REMINDER_HOURS,
            DREAMS_TESTIMONIAL_REQUEST_DAYS,
            AUTH_MAGIC_TTL_MINUTES, AUTH_ACCESS_TTL_MINUTES, AUTH_REFRESH_TTL_DAYS, AUTH_SESSION_TTL_HOURS,
            AUTH_KNOWN_DEVICE_DAYS,
            AUTH_PASSKEY_CHALLENGE_TTL_SECONDS,
            ORDERS_EXPIRE_MINUTES, CREDITS_EXPIRY_NOTICE_DAYS,
            INTERPRETER_EMAILS, INTERPRETER_DIGEST_HOUR,
            PRICING_GLOBAL_CURRENCY, PRICING_DEFAULT_COUNTRY, PRICING_FX_TO_USD,
            YOUTUBE_POLL_MINUTES,
            STATS_SUBSCRIBERS, STATS_VIEWS, STATS_VIDEOS,
            SCHEDULE_TIME_ZONE,
            MAIL_EVENT_WELCOME, MAIL_EVENT_PAYMENT_FAILED, MAIL_EVENT_DREAM_CANCELLED, MAIL_EVENT_CREDITS_ADJUSTED,
            MAIL_EVENT_TESTIMONIAL_APPROVED, MAIL_EVENT_ACCOUNT_DELETED, MAIL_EVENT_NEW_USER,
            MAIL_EVENT_TESTIMONIAL_RECEIVED, MAIL_EVENT_DREAM_SUBMITTED, MAIL_EVENT_DREAM_RECEIVED,
            MAIL_EVENT_INTERPRETER_QUESTION, MAIL_EVENT_USER_REPLIED, MAIL_EVENT_INTERPRETATION_READY,
            MAIL_EVENT_PAYMENT_RECEIPT, MAIL_EVENT_PAYMENT_SUSPICIOUS, MAIL_EVENT_REPLY_REMINDER,
            MAIL_EVENT_TESTIMONIAL_REQUEST, MAIL_EVENT_INTERPRETER_DIGEST, MAIL_EVENT_YOUTUBE_NEW_VIDEO,
            MAIL_EVENT_SUPPORT_AUTO_REPLY, MAIL_EVENT_NEW_SIGN_IN,
            MAIL_EVENT_PASSKEY_ADDED, MAIL_EVENT_SUPPORT_IN_PROGRESS, MAIL_EVENT_SUPPORT_CLOSED,
            MAIL_THEME_DEFAULT, MAIL_ASSETS_BASE_URL, MAIL_AUTO_REPLY_COOLDOWN_HOURS, MAIL_SAVE_TO_SENT, MAIL_SENT_FOLDER,
            INSIGHTS_AWAITING_REPLY_DAYS, INSIGHTS_TRAFFIC_CHANGE_PERCENT, INSIGHTS_STREAK_MIN_DAYS,
            REPORTS_EXCEL_MAX_ROWS);
}
