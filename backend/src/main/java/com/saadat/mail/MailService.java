package com.saadat.mail;

import com.saadat.common.domain.EmailStatus;
import com.saadat.common.web.LogMask;
import com.saadat.config.props.AppProperties;
import com.saadat.notifications.domain.EmailLog;
import com.saadat.notifications.repo.EmailLogRepository;
import com.saadat.settings.SettingKeys;
import com.saadat.settings.SettingsService;
import jakarta.mail.internet.MimeMessage;
import java.time.Clock;
import java.time.Year;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.thymeleaf.ITemplateEngine;
import org.thymeleaf.context.Context;

/**
 * Renders {@code templates/mail/<template>_<ar|en>.html} (fallback {@code _ar}) with Thymeleaf and sends it via
 * {@link JavaMailSender} (UTF-8 HTML). Every attempt lands in {@code email_log} (SENT / FAILED / LOGGED).
 * When {@code spring.mail.host} is blank the rendered HTML is logged at INFO and the status is LOGGED.
 * Delivery is retried {@link #MAX_ATTEMPTS} times with exponential backoff.
 *
 * <p>Common model entries added to every e-mail: {@code brandName}, {@code tagline}, {@code supportEmail},
 * {@code brandNameAr}, {@code brandNameEn}, {@code youtubeUrl} (setting brand.youtube_url, else the site URL), {@code frontendUrl}, {@code siteHost}
 * (frontendUrl without the scheme), {@code subject}, {@code preheader}, {@code year}, {@code fontAr} /
 * {@code fontEn} (font stacks; the layout picks one by direction), {@code theme} (the template's resolved
 * {@link MailThemeView}: colours + absolute header/footer image URLs) and {@code ctaUrl} (derived from
 * {@code link} when the caller passes an SPA path).
 */
@Slf4j
@Service
public class MailService {

    public static final int MAX_ATTEMPTS = 3;
    static final long BASE_BACKOFF_MILLIS = 1000L;

    static final String TEMPLATE_DIR = "mail/";
    static final String CLASSPATH_TEMPLATES = "templates/";
    static final String TEMPLATE_SUFFIX = ".html";
    static final String SUBJECT_KEY = "mail.%s.subject";
    static final String PREHEADER_KEY = "mail.%s.preheader";

    public static final String MODEL_LINK = "link";
    public static final String MODEL_CTA_URL = "ctaUrl";
    public static final String MODEL_THEME = "theme";

    /** Font stacks of the e-mail layout: Arabic (rtl) and English (ltr). */
    public static final String FONT_AR = "'IBM Plex Sans Arabic', 'IBM Plex Sans', Tahoma, Arial, sans-serif";
    public static final String FONT_EN = "'IBM Plex Sans', 'IBM Plex Sans Arabic', 'Segoe UI', Arial, sans-serif";

    private static final int SUBJECT_MAX = 500;
    private static final int ERROR_MAX = 2000;

    private final ITemplateEngine templateEngine;
    private final ObjectProvider<JavaMailSender> mailSender;
    private final EmailLogRepository emailLogRepository;
    private final MessageText messageText;
    private final SettingsService settings;
    private final AppProperties properties;
    private final MailThemeService themeService;
    private final Clock clock;
    private final String smtpHost;

    public MailService(ITemplateEngine templateEngine, ObjectProvider<JavaMailSender> mailSender,
                       EmailLogRepository emailLogRepository, MessageText messageText, SettingsService settings,
                       AppProperties properties, MailThemeService themeService, Clock clock,
                       @Value("${spring.mail.host:}") String smtpHost) {
        this.templateEngine = templateEngine;
        this.mailSender = mailSender;
        this.emailLogRepository = emailLogRepository;
        this.messageText = messageText;
        this.settings = settings;
        this.properties = properties;
        this.themeService = themeService;
        this.clock = clock;
        this.smtpHost = smtpHost == null ? "" : smtpHost.trim();
    }

    // ------------------------------------------------------------------ public API

    /** Renders and sends synchronously (with retries); never throws — the outcome is returned and logged. */
    public EmailStatus send(String to, String template, com.saadat.common.domain.Locale locale,
                            Map<String, Object> model, String ref) {
        return send(null, to, template, locale, model, ref);
    }

    /**
     * Same as {@link #send(String, String, com.saadat.common.domain.Locale, Map, String)}, recording the user id.
     * Returns {@link EmailStatus#DISABLED} without rendering or logging when the event is switched off.
     */
    public EmailStatus send(UUID userId, String to, String template, com.saadat.common.domain.Locale locale,
                            Map<String, Object> model, String ref) {
        if (!isEnabled(template)) {
            log.debug("E-mail event '{}' is switched off ({})", template, SettingKeys.mailEvent(template));
            return EmailStatus.DISABLED;
        }
        final RenderedMail mail;
        try {
            mail = render(template, locale, model);
        } catch (RuntimeException e) {
            log.error("E-mail template {} failed to render", template, e);
            record(userId, to, template, ref, null, EmailStatus.FAILED, null, e.toString());
            return EmailStatus.FAILED;
        }

        JavaMailSender sender = smtpHost.isEmpty() ? null : mailSender.getIfAvailable();
        if (sender == null) {
            log.info("SMTP not configured — e-mail '{}' to {} (subject: {}):\n{}",
                    template, LogMask.email(to), mail.subject(), mail.html());
            record(userId, to, template, ref, mail.subject(), EmailStatus.LOGGED, null, null);
            return EmailStatus.LOGGED;
        }

        String lastError = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                String messageId = deliver(sender, to, mail);
                record(userId, to, template, ref, mail.subject(), EmailStatus.SENT, messageId, null);
                return EmailStatus.SENT;
            } catch (Exception e) {
                lastError = e.toString();
                log.warn("E-mail '{}' to {} failed (attempt {}/{}): {}", template, LogMask.email(to), attempt,
                        MAX_ATTEMPTS, e.getMessage());
                if (attempt < MAX_ATTEMPTS && !sleep(BASE_BACKOFF_MILLIS << (attempt - 1))) {
                    break;
                }
            }
        }
        record(userId, to, template, ref, mail.subject(), EmailStatus.FAILED, null, lastError);
        return EmailStatus.FAILED;
    }

    /**
     * Whether the e-mail event is switched on: BOOL setting {@code mail.event.<template>} (missing = on). The
     * sign-in e-mail ({@link MailTemplates#MAGIC_LINK}) can never be switched off.
     */
    public boolean isEnabled(String template) {
        if (MailTemplates.MAGIC_LINK.equals(template)) {
            return true;
        }
        return settings.getBool(SettingKeys.mailEvent(template), true);
    }

    /** Fire-and-forget variant on the async pool (use from request threads). */
    @Async
    public void sendAsync(UUID userId, String to, String template, com.saadat.common.domain.Locale locale,
                          Map<String, Object> model, String ref) {
        send(userId, to, template, locale, model, ref);
    }

    /** Renders subject, preheader and HTML without sending (also used by tests), in the template's theme. */
    public RenderedMail render(String template, com.saadat.common.domain.Locale locale, Map<String, Object> model) {
        return render(template, locale, model, null);
    }

    /**
     * Same as {@link #render(String, com.saadat.common.domain.Locale, Map)} but in theme {@code themeKeyOverride}
     * when it names a known theme (admin preview); null/blank/unknown = the template's configured theme.
     */
    public RenderedMail render(String template, com.saadat.common.domain.Locale locale, Map<String, Object> model,
                               String themeKeyOverride) {
        com.saadat.common.domain.Locale loc = locale == null ? com.saadat.common.domain.Locale.AR : locale;
        Map<String, Object> vars = messageText.formatValues(model, loc);
        addCommon(vars, loc, template, themeKeyOverride);
        String subject = messageText.get(String.format(SUBJECT_KEY, template), loc, vars);
        String preheader = messageText.get(String.format(PREHEADER_KEY, template), loc, vars);
        vars.put("subject", subject);
        vars.put("preheader", preheader);

        Context context = new Context(MessageText.toJava(loc), vars);
        String html = templateEngine.process(resolveTemplate(template, loc), context);
        return new RenderedMail(subject, html);
    }

    // ------------------------------------------------------------------ internals

    private void addCommon(Map<String, Object> vars, com.saadat.common.domain.Locale loc, String template,
                           String themeKeyOverride) {
        boolean en = loc == com.saadat.common.domain.Locale.EN;
        String frontend = trimSlash(properties.getFrontendUrl());
        String youtube = settings.getString(SettingKeys.BRAND_YOUTUBE_URL, "");
        vars.putIfAbsent("brandName",
                settings.getString(en ? SettingKeys.BRAND_NAME_EN : SettingKeys.BRAND_NAME_AR, ""));
        vars.putIfAbsent("tagline",
                settings.getString(en ? SettingKeys.BRAND_TAGLINE_EN : SettingKeys.BRAND_TAGLINE_AR, ""));
        // both names, for bilingual e-mails (support-auto-reply)
        vars.putIfAbsent("brandNameAr", settings.getString(SettingKeys.BRAND_NAME_AR, ""));
        vars.putIfAbsent("brandNameEn", settings.getString(SettingKeys.BRAND_NAME_EN, ""));
        vars.putIfAbsent("supportEmail", settings.getString(SettingKeys.BRAND_SUPPORT_EMAIL, ""));
        vars.putIfAbsent("youtubeUrl", youtube == null || youtube.isBlank() ? frontend : youtube.trim());
        vars.putIfAbsent("frontendUrl", frontend);
        vars.putIfAbsent("siteHost", hostOf(frontend));
        vars.putIfAbsent("year", Year.now(clock).getValue());
        vars.put("fontAr", FONT_AR);
        vars.put("fontEn", FONT_EN);
        vars.put(MODEL_THEME, themeService.view(themeService.resolve(template, themeKeyOverride)));
        // the "this concerns your account" footer note is wrong for replies to people who may have no account
        vars.putIfAbsent("showAccountNote", !MailTemplates.SUPPORT_AUTO_REPLY.equals(template));
        Object link = vars.get(MODEL_LINK);
        if (!vars.containsKey(MODEL_CTA_URL)) {
            if (link instanceof String s && s.startsWith("/")) {
                vars.put(MODEL_CTA_URL, frontend + s);
            } else if (link instanceof String s && !s.isBlank()) {
                vars.put(MODEL_CTA_URL, s);
            } else {
                vars.put(MODEL_CTA_URL, frontend);
            }
        }
    }

    /** {@code mail/<template>_<locale>} when the file exists, else the Arabic variant. */
    String resolveTemplate(String template, com.saadat.common.domain.Locale loc) {
        String localized = TEMPLATE_DIR + template + "_" + loc.code();
        if (new ClassPathResource(CLASSPATH_TEMPLATES + localized + TEMPLATE_SUFFIX).exists()) {
            return localized;
        }
        return TEMPLATE_DIR + template + "_" + com.saadat.common.domain.Locale.AR.code();
    }

    private String deliver(JavaMailSender sender, String to, RenderedMail mail) throws Exception {
        MimeMessage message = sender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
        helper.setTo(to);
        String from = properties.getMail().getFrom();
        if (from == null || from.isBlank()) {
            from = settings.getString(SettingKeys.BRAND_SUPPORT_EMAIL, "");
        }
        String fromName = properties.getMail().getFromName();
        if (fromName == null || fromName.isBlank()) {
            helper.setFrom(from);
        } else {
            helper.setFrom(from, fromName);
        }
        helper.setSubject(mail.subject());
        helper.setText(mail.html(), true);
        sender.send(message);
        return message.getMessageID();
    }

    private void record(UUID userId, String to, String template, String ref, String subject, EmailStatus status,
                        String providerId, String error) {
        try {
            EmailLog row = new EmailLog();
            row.setUserId(userId);
            row.setToEmail(to == null ? "" : to);
            row.setTemplate(template);
            row.setRef(ref);
            row.setSubject(truncate(subject, SUBJECT_MAX));
            row.setStatus(status);
            row.setProviderId(providerId);
            row.setError(truncate(error, ERROR_MAX));
            row.setCreatedAt(clock.instant());
            emailLogRepository.save(row);
        } catch (RuntimeException e) {
            log.error("Could not write email_log for template {}", template, e);
        }
    }

    private static boolean sleep(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() > max ? value.substring(0, max) : value;
    }

    /** {@code https://saadatu-aldarein.com/} → {@code saadatu-aldarein.com}. */
    static String hostOf(String url) {
        return trimSlash(url).replaceFirst("^[A-Za-z][A-Za-z0-9+.-]*://", "");
    }

    private static String trimSlash(String url) {
        String u = url == null ? "" : url.trim();
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        return u;
    }

    /** A rendered e-mail. */
    public record RenderedMail(String subject, String html) {
    }
}
