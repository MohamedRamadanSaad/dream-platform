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
    public static final String AUTH_REFRESH_TTL_DAYS = "auth.refresh_ttl_days";

    // ---- orders ----
    public static final String ORDERS_EXPIRE_MINUTES = "orders.expire_minutes";

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
    /** IANA zone used for daily schedules (digest). */
    public static final String SCHEDULE_TIME_ZONE = "schedule.time_zone";

    /** Every key seeded by the migrations. */
    public static final List<String> ALL = List.of(
            BRAND_NAME_AR, BRAND_NAME_EN, BRAND_TAGLINE_AR, BRAND_TAGLINE_EN, BRAND_SUPPORT_EMAIL,
            BRAND_YOUTUBE_URL, BRAND_YOUTUBE_CHANNEL_ID,
            WAIT_BUSY, WAIT_NORMAL_HOURS, WAIT_BUSY_MIN_DAYS, WAIT_BUSY_MAX_DAYS, WAIT_MESSAGE_AR, WAIT_MESSAGE_EN,
            WAIT_AUTO_RESET_AT,
            DREAMS_MIN_CHARS, DREAMS_MAX_CHARS, DREAMS_DRAFT_LIMIT, DREAMS_REPLY_REMINDER_HOURS,
            DREAMS_TESTIMONIAL_REQUEST_DAYS,
            AUTH_MAGIC_TTL_MINUTES, AUTH_ACCESS_TTL_MINUTES, AUTH_REFRESH_TTL_DAYS,
            ORDERS_EXPIRE_MINUTES,
            INTERPRETER_EMAILS, INTERPRETER_DIGEST_HOUR,
            PRICING_GLOBAL_CURRENCY, PRICING_DEFAULT_COUNTRY, PRICING_FX_TO_USD,
            YOUTUBE_POLL_MINUTES,
            STATS_SUBSCRIBERS, STATS_VIEWS, STATS_VIDEOS,
            SCHEDULE_TIME_ZONE);
}
