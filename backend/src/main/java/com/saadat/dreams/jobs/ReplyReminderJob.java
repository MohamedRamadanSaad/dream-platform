package com.saadat.dreams.jobs;

import com.saadat.common.domain.DreamStatus;
import com.saadat.dreams.domain.Dream;
import com.saadat.dreams.repo.DreamRepository;
import com.saadat.dreams.service.DreamLinks;
import com.saadat.dreams.service.DreamMapper;
import com.saadat.mail.MailService;
import com.saadat.mail.MailTemplates;
import com.saadat.notifications.repo.EmailLogRepository;
import com.saadat.settings.SettingKeys;
import com.saadat.settings.SettingsService;
import com.saadat.users.domain.User;
import com.saadat.users.repo.UserRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Hourly: a dream waiting in AWAITING_USER_REPLY for more than {@code dreams.reply_reminder_hours} gets ONE
 * reminder e-mail (deduplicated through email_log ref {@code reply-reminder:<dreamId>}).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReplyReminderJob {

    public static final String TEMPLATE = MailTemplates.REPLY_REMINDER;
    public static final String REF_PREFIX = "reply-reminder:";

    private final DreamRepository dreamRepository;
    private final UserRepository userRepository;
    private final EmailLogRepository emailLogRepository;
    private final MailService mailService;
    private final SettingsService settingsService;
    private final Clock clock;

    @Scheduled(cron = "0 10 * * * *")
    public void run() {
        try {
            int sent = sendDue();
            if (sent > 0) {
                log.info("Sent {} reply reminders", sent);
            }
        } catch (RuntimeException e) {
            log.error("Reply reminder job failed", e);
        }
    }

    public int sendDue() {
        Instant threshold = clock.instant()
                .minus(Duration.ofHours(settingsService.getInt(SettingKeys.DREAMS_REPLY_REMINDER_HOURS)));
        int sent = 0;
        for (Dream d : dreamRepository.findByStatusAndSlaPausedAtBefore(DreamStatus.AWAITING_USER_REPLY, threshold)) {
            String ref = REF_PREFIX + d.getId();
            if (emailLogRepository.existsByTemplateAndRef(TEMPLATE, ref)) {
                continue;
            }
            User user = userRepository.findById(d.getUserId()).orElse(null);
            if (user == null || user.isDeleted()) {
                continue;
            }
            Map<String, Object> model = new LinkedHashMap<>();
            model.put("dreamId", d.getId().toString());
            model.put("excerpt", DreamMapper.excerpt(d.getText()));
            model.put("userName", user.getName());
            model.put("name", user.getName());
            model.put("link", DreamLinks.user(d.getId()));
            try {
                mailService.send(user.getId(), user.getEmail(), TEMPLATE, user.getLocale(), model, ref);
                sent++;
            } catch (RuntimeException e) {
                log.warn("Reply reminder for dream {} failed: {}", d.getId(), e.toString());
            }
        }
        return sent;
    }
}
