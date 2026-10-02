package com.saadat.mail;

import com.saadat.common.domain.Locale;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Plausible sample models for the admin e-mail preview (GET /admin/mail/preview). Never used for real e-mails.
 * Names are «أحمد» (Arabic) / "Ahmed" (English); amounts and dates are examples only.
 */
@Component
public class MailSamples {

    static final String SAMPLE_NAME_AR = "أحمد";
    static final String SAMPLE_NAME_EN = "Ahmed";
    private static final String SAMPLE_DREAM_ID = "00000000-0000-4000-8000-000000000001";
    private static final String SAMPLE_USER_ID = "00000000-0000-4000-8000-000000000002";

    private final Clock clock;

    public MailSamples(Clock clock) {
        this.clock = clock;
    }

    /** A sample model for {@code template} in {@code locale} (unknown templates get the common keys only). */
    public Map<String, Object> model(String template, Locale locale) {
        boolean en = locale == Locale.EN;
        String name = en ? SAMPLE_NAME_EN : SAMPLE_NAME_AR;
        Instant now = clock.instant();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("userName", name);
        m.put("link", FrontendPaths.ME);
        switch (template == null ? "" : template) {
            case MailTemplates.MAGIC_LINK -> {
                m.put("link", FrontendPaths.MAGIC_CALLBACK + "?" + FrontendPaths.MAGIC_CALLBACK_TOKEN_PARAM + "=preview");
                m.put("code", "482915");
                m.put("ttlMinutes", 15);
            }
            case MailTemplates.DREAM_RECEIVED -> {
                m.put("expectedBy", now.plus(Duration.ofHours(48)));
                m.put("waitMessage", en ? "We usually reply within 48 hours." : "نرد عادةً خلال 48 ساعة.");
                m.put("link", FrontendPaths.myDream(SAMPLE_DREAM_ID));
            }
            case MailTemplates.INTERPRETER_QUESTION, MailTemplates.INTERPRETATION_READY,
                 MailTemplates.REPLY_REMINDER, MailTemplates.TESTIMONIAL_REQUEST ->
                    m.put("link", FrontendPaths.myDream(SAMPLE_DREAM_ID));
            case MailTemplates.USER_REPLIED -> m.put("link", FrontendPaths.adminDream(SAMPLE_DREAM_ID));
            case MailTemplates.DREAM_SUBMITTED -> {
                m.put("expectedBy", now.plus(Duration.ofHours(48)));
                m.put("link", FrontendPaths.adminDream(SAMPLE_DREAM_ID));
            }
            case MailTemplates.PAYMENT_RECEIPT -> {
                m.put("packageName", en ? "Three dreams" : "ثلاث رؤى");
                m.put("amount", new BigDecimal("150.00"));
                m.put("currency", "SAR");
                m.put("credits", 3);
                m.put("orderRef", "A1B2C3D4");
                m.put("paidAt", now);
                m.put("link", FrontendPaths.MY_PAYMENTS);
            }
            case MailTemplates.PAYMENT_FAILED -> {
                m.put("packageName", en ? "Three dreams" : "ثلاث رؤى");
                m.put("amount", new BigDecimal("150.00"));
                m.put("currency", "SAR");
                m.put("orderRef", "A1B2C3D4");
                m.put("link", FrontendPaths.PACKAGES);
            }
            case MailTemplates.PAYMENT_SUSPICIOUS -> {
                m.put("orderRef", "A1B2C3D4");
                m.put("userEmail", "ahmed@example.com");
                m.put("amount", new BigDecimal("150.00"));
                m.put("currency", "SAR");
                m.put("reason", "COUNTRY_MISMATCH");
                m.put("link", FrontendPaths.adminUser(SAMPLE_USER_ID));
            }
            case MailTemplates.INTERPRETER_DIGEST -> {
                m.put("waiting", 7);
                m.put("overdue", 2);
                m.put("awaitingReply", 3);
                m.put("link", FrontendPaths.ADMIN_DREAMS);
            }
            case MailTemplates.YOUTUBE_NEW_VIDEO -> {
                m.put("videoTitle", en ? "Seeing water in a dream" : "رؤية الماء في المنام");
                m.put("videoUrl", "https://www.youtube.com/@almoaberafatema");
                m.put("thumbnailUrl", "");
            }
            case MailTemplates.WELCOME -> m.put("link", FrontendPaths.NEW_DREAM);
            case MailTemplates.DREAM_CANCELLED -> {
                m.put("excerpt", en ? "I saw a quiet river under a bright moon…" : "رأيت نهرًا هادئًا تحت قمر مضيء…");
                m.put("reason", en ? "The dream was sent twice." : "أُرسلت الرؤيا مرتين.");
                m.put("refunded", true);
                m.put("balance", 2);
                m.put("link", FrontendPaths.MY_DREAMS);
            }
            case MailTemplates.CREDITS_ADJUSTED -> {
                m.put("delta", "+2");
                m.put("reason", en ? "A gift from us" : "هدية منّا");
                m.put("balance", 5);
            }
            case MailTemplates.TESTIMONIAL_APPROVED, MailTemplates.TESTIMONIAL_RECEIVED -> {
                m.put("rating", 5);
                m.put("comment", en ? "May God reward you. A clear and calm interpretation." : "جزاكم الله خيرًا، تفسير واضح ومطمئن.");
                if (MailTemplates.TESTIMONIAL_RECEIVED.equals(template)) {
                    m.put("link", FrontendPaths.ADMIN_TESTIMONIALS);
                }
            }
            case MailTemplates.NEW_USER -> {
                m.put("countryName", en ? "Saudi Arabia" : "السعودية");
                m.put("age", 31);
                m.put("gender", en ? "Male" : "ذكر");
                m.put("link", FrontendPaths.adminUser(SAMPLE_USER_ID));
            }
            case MailTemplates.NEW_SIGN_IN -> {
                m.put("device", "Chrome · Windows");
                m.put("deviceType", en ? "Computer" : "حاسوب");
                m.put("countryName", en ? "Saudi Arabia" : "السعودية");
                m.put("signedInAt", now);
                m.put("link", FrontendPaths.MY_DEVICES);
            }
            default -> {
                // account-deleted, support-auto-reply: name only (or nothing)
            }
        }
        return m;
    }
}
