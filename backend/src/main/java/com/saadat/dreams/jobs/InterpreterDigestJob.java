package com.saadat.dreams.jobs;

import com.saadat.common.domain.DreamStatus;
import com.saadat.dreams.repo.DreamRepository;
import com.saadat.dreams.service.DreamLinks;
import com.saadat.mail.MailService;
import com.saadat.mail.MailTemplates;
import com.saadat.notifications.repo.EmailLogRepository;
import com.saadat.notifications.service.InterpreterDirectory;
import com.saadat.settings.SettingKeys;
import com.saadat.settings.SettingsService;
import com.saadat.users.domain.User;
import com.saadat.users.repo.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Interpreter daily digest (N waiting, M overdue) at {@code interpreter.digest_hour} in
 * {@code schedule.time_zone}. A cron cannot read settings, so this runs hourly and acts when the local hour
 * matches and no digest was logged for that local date (email_log ref {@code digest:<yyyy-MM-dd>}).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InterpreterDigestJob {

    public static final String TEMPLATE = MailTemplates.INTERPRETER_DIGEST;
    public static final String REF_PREFIX = "digest:";

    private final DreamRepository dreamRepository;
    private final UserRepository userRepository;
    private final EmailLogRepository emailLogRepository;
    private final InterpreterDirectory interpreterDirectory;
    private final MailService mailService;
    private final SettingsService settingsService;
    private final Clock clock;

    @Scheduled(cron = "0 1 * * * *")
    public void run() {
        try {
            sendIfDue();
        } catch (RuntimeException e) {
            log.error("Interpreter digest job failed", e);
        }
    }

    /** @return true when the digest was sent in this run */
    public boolean sendIfDue() {
        ZoneId zone = ZoneId.of(settingsService.getString(SettingKeys.SCHEDULE_TIME_ZONE).trim());
        Instant now = clock.instant();
        ZonedDateTime local = now.atZone(zone);
        if (local.getHour() != settingsService.getInt(SettingKeys.INTERPRETER_DIGEST_HOUR)) {
            return false;
        }
        LocalDate date = local.toLocalDate();
        String ref = REF_PREFIX + date;
        if (emailLogRepository.existsByTemplateAndRef(TEMPLATE, ref)) {
            return false;
        }
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("date", date.toString());
        model.put("waiting", dreamRepository.countByStatus(DreamStatus.IN_REVIEW));
        model.put("awaitingReply", dreamRepository.countByStatus(DreamStatus.AWAITING_USER_REPLY));
        model.put("overdue", dreamRepository.countByStatusAndExpectedByBefore(DreamStatus.IN_REVIEW, now));
        model.put("link", DreamLinks.ADMIN_QUEUE);
        boolean sent = false;
        for (User interpreter : userRepository.findAllById(interpreterDirectory.interpreterUserIds())) {
            try {
                Map<String, Object> personal = new LinkedHashMap<>(model);
                personal.put("name", interpreter.getName());
                mailService.send(interpreter.getId(), interpreter.getEmail(), TEMPLATE, interpreter.getLocale(),
                        personal, ref);
                sent = true;
            } catch (RuntimeException e) {
                log.warn("Digest to interpreter {} failed: {}", interpreter.getId(), e.toString());
            }
        }
        return sent;
    }
}
