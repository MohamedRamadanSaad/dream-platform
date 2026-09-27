package com.saadat.dreams.jobs;

import com.saadat.common.domain.DreamStatus;
import com.saadat.dreams.domain.Dream;
import com.saadat.dreams.repo.DreamRepository;
import com.saadat.dreams.repo.TestimonialRepository;
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
 * Daily: {@code dreams.testimonial_request_days} after INTERPRETED, if the dream has no testimonial, ONE
 * e-mail asking for one (deduplicated through email_log ref {@code testimonial-request:<dreamId>}). Only
 * dreams interpreted within a bounded window are considered, so old history is never mass-mailed.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TestimonialRequestJob {

    public static final String TEMPLATE = MailTemplates.TESTIMONIAL_REQUEST;
    public static final String REF_PREFIX = "testimonial-request:";
    /** How far past the due date a missed request is still sent (e.g. after downtime). */
    static final Duration CATCH_UP_WINDOW = Duration.ofDays(14);

    private final DreamRepository dreamRepository;
    private final TestimonialRepository testimonialRepository;
    private final UserRepository userRepository;
    private final EmailLogRepository emailLogRepository;
    private final MailService mailService;
    private final SettingsService settingsService;
    private final Clock clock;

    @Scheduled(cron = "0 20 10 * * *", zone = "UTC")
    public void run() {
        try {
            int sent = sendDue();
            if (sent > 0) {
                log.info("Sent {} testimonial requests", sent);
            }
        } catch (RuntimeException e) {
            log.error("Testimonial request job failed", e);
        }
    }

    public int sendDue() {
        Instant due = clock.instant()
                .minus(Duration.ofDays(settingsService.getInt(SettingKeys.DREAMS_TESTIMONIAL_REQUEST_DAYS)));
        Instant oldest = due.minus(CATCH_UP_WINDOW);
        int sent = 0;
        for (Dream d : dreamRepository.findByStatusAndInterpretedAtBefore(DreamStatus.INTERPRETED, due)) {
            if (d.getInterpretedAt() == null || d.getInterpretedAt().isBefore(oldest)) {
                continue;
            }
            String ref = REF_PREFIX + d.getId();
            if (testimonialRepository.existsByDreamId(d.getId()) || emailLogRepository.existsByTemplateAndRef(TEMPLATE, ref)) {
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
                log.warn("Testimonial request for dream {} failed: {}", d.getId(), e.toString());
            }
        }
        return sent;
    }
}
