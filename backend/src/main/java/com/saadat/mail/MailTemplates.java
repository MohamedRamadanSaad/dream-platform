package com.saadat.mail;

import java.util.List;

/**
 * Names of the Thymeleaf e-mail templates ({@code templates/mail/<name>_<ar|en>.html}). Subject and preheader
 * come from messages {@code mail.<name>.subject} / {@code mail.<name>.preheader} ({@code {key}} placeholders
 * are filled from the model).
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
 * </ul>
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

    public static final List<String> ALL = List.of(
            MAGIC_LINK, DREAM_RECEIVED, INTERPRETER_QUESTION, USER_REPLIED, INTERPRETATION_READY,
            PAYMENT_RECEIPT, PAYMENT_SUSPICIOUS, REPLY_REMINDER, TESTIMONIAL_REQUEST, INTERPRETER_DIGEST,
            DREAM_SUBMITTED, YOUTUBE_NEW_VIDEO);

    private MailTemplates() {
    }
}
