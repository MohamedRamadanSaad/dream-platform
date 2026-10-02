package com.saadat.mail;

import java.util.List;

/**
 * Names of the Thymeleaf e-mail templates ({@code templates/mail/<name>_<ar|en>.html}). Subject and preheader
 * come from messages {@code mail.<name>.subject} / {@code mail.<name>.preheader} ({@code {key}} placeholders
 * are filled from the model). Every template except {@link #MAGIC_LINK} can be switched off with the BOOL setting
 * {@code mail.event.<name>} (see MailService).
 *
 * <p>Model keys each template reads (all optional unless noted; Instants are formatted automatically in the
 * recipient's locale and {@code schedule.time_zone}; {@code link} = SPA path → {@code ctaUrl} is derived):
 * <ul>
 *   <li>{@link #MAGIC_LINK}: link*, code*, ttlMinutes*</li>
 *   <li>{@link #DREAM_RECEIVED}: name, expectedBy, waitMessage, link</li>
 *   <li>{@link #INTERPRETER_QUESTION}: name, link</li>
 *   <li>{@link #USER_REPLIED} (to interpreter): userName, link</li>
 *   <li>{@link #INTERPRETATION_READY}: name, link (no interpretation text by design)</li>
 *   <li>{@link #PAYMENT_RECEIPT}: name, packageName, amount, currency, credits, orderRef, paidAt, link</li>
 *   <li>{@link #PAYMENT_SUSPICIOUS} (to interpreter): orderRef, userName, userEmail, amount, currency, reason, link</li>
 *   <li>{@link #REPLY_REMINDER}: name, link</li>
 *   <li>{@link #TESTIMONIAL_REQUEST}: name, link</li>
 *   <li>{@link #INTERPRETER_DIGEST}: waiting*, overdue*, awaitingReply, link</li>
 *   <li>{@link #DREAM_SUBMITTED} (to interpreter): userName, expectedBy, link</li>
 *   <li>{@link #YOUTUBE_NEW_VIDEO}: name, videoTitle, videoUrl*, thumbnailUrl</li>
 *   <li>{@link #WELCOME}: name, link</li>
 *   <li>{@link #PAYMENT_FAILED}: name, packageName, amount, currency, orderRef, link</li>
 *   <li>{@link #DREAM_CANCELLED}: name, excerpt, reason, refunded (boolean), balance, link</li>
 *   <li>{@link #CREDITS_ADJUSTED}: name, delta (signed text, e.g. "+2"), reason, balance, link</li>
 *   <li>{@link #TESTIMONIAL_APPROVED}: name, rating, comment, link</li>
 *   <li>{@link #ACCOUNT_DELETED}: name (sent to the address captured before anonymising)</li>
 *   <li>{@link #NEW_USER} (to interpreter): userName, countryName, age, gender, link</li>
 *   <li>{@link #TESTIMONIAL_RECEIVED} (to interpreter): userName, rating, comment, link</li>
 *   <li>{@link #SUPPORT_AUTO_REPLY} (to whoever wrote to the support mailbox): none — one bilingual e-mail
 *       (Arabic then English) with the same content in both locale files; no call-to-action</li>
 *   <li>{@link #NEW_SIGN_IN}: name, device ("Chrome · Windows"), deviceType (localized), countryName, signedInAt,
 *       link (the devices list of the account's profile page)</li>
 * </ul>
 *
 * <p>Each template's header/footer theme comes from STRING setting {@code mail.theme.<name>} (see
 * MailThemeService); the wording never depends on the theme.
 */
public final class MailTemplates {

    public static final String MAGIC_LINK = "magic-link";
    public static final String DREAM_RECEIVED = "dream-received";
    public static final String INTERPRETER_QUESTION = "interpreter-question";
    public static final String USER_REPLIED = "user-replied";
    public static final String INTERPRETATION_READY = "interpretation-ready";
    public static final String PAYMENT_RECEIPT = "payment-receipt";
    public static final String PAYMENT_SUSPICIOUS = "payment-suspicious";
    public static final String REPLY_REMINDER = "reply-reminder";
    public static final String TESTIMONIAL_REQUEST = "testimonial-request";
    public static final String INTERPRETER_DIGEST = "interpreter-digest";
    public static final String DREAM_SUBMITTED = "dream-submitted";
    public static final String YOUTUBE_NEW_VIDEO = "youtube-new-video";
    public static final String WELCOME = "welcome";
    public static final String PAYMENT_FAILED = "payment-failed";
    public static final String DREAM_CANCELLED = "dream-cancelled";
    public static final String CREDITS_ADJUSTED = "credits-adjusted";
    public static final String TESTIMONIAL_APPROVED = "testimonial-approved";
    public static final String ACCOUNT_DELETED = "account-deleted";
    public static final String NEW_USER = "new-user";
    public static final String TESTIMONIAL_RECEIVED = "testimonial-received";
    public static final String SUPPORT_AUTO_REPLY = "support-auto-reply";
    /** Security alert after a sign-in from a browser + system the account did not use recently. */
    public static final String NEW_SIGN_IN = "new-sign-in";

    public static final List<String> ALL = List.of(
            MAGIC_LINK, DREAM_RECEIVED, INTERPRETER_QUESTION, USER_REPLIED, INTERPRETATION_READY,
            PAYMENT_RECEIPT, PAYMENT_SUSPICIOUS, REPLY_REMINDER, TESTIMONIAL_REQUEST, INTERPRETER_DIGEST,
            DREAM_SUBMITTED, YOUTUBE_NEW_VIDEO, WELCOME, PAYMENT_FAILED, DREAM_CANCELLED, CREDITS_ADJUSTED,
            TESTIMONIAL_APPROVED, ACCOUNT_DELETED, NEW_USER, TESTIMONIAL_RECEIVED, SUPPORT_AUTO_REPLY, NEW_SIGN_IN);

    private MailTemplates() {
    }
}
