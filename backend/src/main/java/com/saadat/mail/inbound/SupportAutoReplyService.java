package com.saadat.mail.inbound;

import com.fasterxml.jackson.databind.JsonNode;
import com.saadat.common.domain.Locale;
import com.saadat.common.web.LogMask;
import com.saadat.config.props.AppProperties;
import com.saadat.mail.MailService;
import com.saadat.mail.MailTemplates;
import com.saadat.mail.inbound.InboundMailParser.InboundMessage;
import com.saadat.notifications.repo.EmailLogRepository;
import com.saadat.settings.SettingKeys;
import com.saadat.settings.SettingsService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Automatic "we received your message" reply to e-mails received by the support mailbox (template
 * {@link MailTemplates#SUPPORT_AUTO_REPLY}, switch {@code mail.event.support-auto-reply}).
 *
 * <p>Loop and abuse guards — the message is skipped (and the reason logged) when: there is no valid sender; the
 * sender is on our own domain ({@code interpreter.email_domain}) or is our own address ({@code brand.support_email}
 * / {@code app.mail.from}); the sender looks automated (noreply, no-reply, mailer-daemon, postmaster, bounce…); the
 * message is automatic or bulk ({@code Auto-Submitted} other than "no", {@code Precedence: bulk|list|junk},
 * {@code List-Id}); or the address already got an auto-reply within {@code mail.auto_reply_cooldown_hours}
 * (from {@code email_log}, plus an in-memory guard for bursts that arrive before the async send is logged).
 */
@Slf4j
@Service
public class SupportAutoReplyService {

    public static final String STATUS_OK = "ok";
    public static final String STATUS_SKIPPED = "skipped";

    public static final String REASON_NO_SENDER = "no-sender";
    public static final String REASON_OWN_ADDRESS = "own-address";
    public static final String REASON_AUTOMATED_SENDER = "automated-sender";
    public static final String REASON_AUTO_SUBMITTED = "auto-submitted";
    public static final String REASON_BULK = "bulk";
    public static final String REASON_MAILING_LIST = "mailing-list";
    public static final String REASON_COOLDOWN = "cooldown";
    public static final String REASON_DISABLED = "disabled";

    static final int DEFAULT_COOLDOWN_HOURS = 24;
    private static final int REF_MAX = 128;
    private static final List<String> AUTOMATED_LOCAL_PARTS = List.of(
            "noreply", "no-reply", "no_reply", "donotreply", "do-not-reply", "mailer-daemon", "mailerdaemon",
            "postmaster", "bounce");
    private static final List<String> BULK_PRECEDENCE = List.of("bulk", "list", "junk");

    private final MailService mailService;
    private final EmailLogRepository emailLogRepository;
    private final SettingsService settings;
    private final AppProperties properties;
    private final Clock clock;

    /** address → when an auto-reply to it was last queued by this instance. */
    private final Map<String, Instant> recentlyQueued = new ConcurrentHashMap<>();

    public SupportAutoReplyService(MailService mailService, EmailLogRepository emailLogRepository,
                                   SettingsService settings, AppProperties properties, Clock clock) {
        this.mailService = mailService;
        this.emailLogRepository = emailLogRepository;
        this.settings = settings;
        this.properties = properties;
        this.clock = clock;
    }

    /** Outcome of one webhook call: {@code ok} (reply queued) or {@code skipped} with a reason. */
    public record Outcome(String status, String reason) {

        static Outcome ok() {
            return new Outcome(STATUS_OK, null);
        }

        static Outcome skipped(String reason) {
            return new Outcome(STATUS_SKIPPED, reason);
        }
    }

    /** Applies the guards and queues the auto-reply. Never throws for payload problems. */
    public Outcome handle(JsonNode payload) {
        InboundMessage message = InboundMailParser.parse(payload);
        String sender = message.sender();
        if (sender == null || sender.isBlank()) {
            return skip(REASON_NO_SENDER, null);
        }
        if (!mailService.isEnabled(MailTemplates.SUPPORT_AUTO_REPLY)) {
            return skip(REASON_DISABLED, sender);
        }
        if (isOwnAddress(sender)) {
            return skip(REASON_OWN_ADDRESS, sender);
        }
        if (isAutomatedSender(sender)) {
            return skip(REASON_AUTOMATED_SENDER, sender);
        }
        for (String value : InboundMailParser.headerValues(payload, "Auto-Submitted")) {
            if (!value.isBlank() && !"no".equalsIgnoreCase(value.trim())) {
                return skip(REASON_AUTO_SUBMITTED, sender);
            }
        }
        for (String value : InboundMailParser.headerValues(payload, "Precedence")) {
            if (BULK_PRECEDENCE.contains(value.trim().toLowerCase(java.util.Locale.ROOT))) {
                return skip(REASON_BULK, sender);
            }
        }
        for (String value : InboundMailParser.headerValues(payload, "List-Id")) {
            if (!value.isBlank()) {
                return skip(REASON_MAILING_LIST, sender);
            }
        }

        Instant now = clock.instant();
        Duration cooldown = Duration.ofHours(Math.max(0,
                settings.getInt(SettingKeys.MAIL_AUTO_REPLY_COOLDOWN_HOURS, DEFAULT_COOLDOWN_HOURS)));
        Instant since = now.minus(cooldown);
        recentlyQueued.values().removeIf(at -> !at.isAfter(since));
        if (emailLogRepository.existsByToEmailIgnoreCaseAndTemplateAndCreatedAtAfter(
                sender, MailTemplates.SUPPORT_AUTO_REPLY, since)) {
            return skip(REASON_COOLDOWN, sender);
        }
        // atomically claim the address so two webhooks arriving together send one reply
        boolean[] claimed = {false};
        recentlyQueued.compute(sender, (k, at) -> {
            if (at != null && at.isAfter(since)) {
                return at;
            }
            claimed[0] = true;
            return now;
        });
        if (!claimed[0]) {
            return skip(REASON_COOLDOWN, sender);
        }

        // the reply never echoes anything from the incoming message (subject included)
        Map<String, Object> model = new LinkedHashMap<>();
        String ref = message.messageId() == null ? null : truncate(message.messageId(), REF_MAX);
        mailService.sendAsync(null, sender, MailTemplates.SUPPORT_AUTO_REPLY, Locale.AR, model, ref);
        log.info("Support auto-reply queued to {}", LogMask.email(sender));
        return Outcome.ok();
    }

    // ------------------------------------------------------------------ internals

    private boolean isOwnAddress(String sender) {
        String domain = settings.getString(SettingKeys.INTERPRETER_EMAIL_DOMAIN, "");
        if (domain != null && !domain.isBlank()) {
            String d = domain.trim().toLowerCase(java.util.Locale.ROOT);
            if (d.startsWith("@")) {
                d = d.substring(1);
            }
            if (sender.endsWith("@" + d)) {
                return true;
            }
        }
        return sameAddress(sender, settings.getString(SettingKeys.BRAND_SUPPORT_EMAIL, ""))
                || sameAddress(sender, properties.getMail().getFrom());
    }

    private static boolean sameAddress(String sender, String own) {
        return own != null && !own.isBlank() && sender.equalsIgnoreCase(own.trim());
    }

    static boolean isAutomatedSender(String sender) {
        int at = sender.indexOf('@');
        String local = (at < 0 ? sender : sender.substring(0, at)).toLowerCase(java.util.Locale.ROOT);
        for (String marker : AUTOMATED_LOCAL_PARTS) {
            if (local.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private static Outcome skip(String reason, String sender) {
        log.info("Support auto-reply skipped ({}) for {}", reason, sender == null ? "-" : LogMask.email(sender));
        return Outcome.skipped(reason);
    }

    private static String truncate(String value, int max) {
        return value.length() > max ? value.substring(0, max) : value;
    }
}
