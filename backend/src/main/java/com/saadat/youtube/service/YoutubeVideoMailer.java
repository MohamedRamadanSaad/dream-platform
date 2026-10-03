package com.saadat.youtube.service;

import com.saadat.common.domain.Role;
import com.saadat.mail.MailService;
import com.saadat.mail.MailTemplates;
import com.saadat.notifications.repo.EmailLogRepository;
import com.saadat.settings.SettingKeys;
import com.saadat.settings.SettingsService;
import com.saadat.users.domain.User;
import com.saadat.users.repo.UserRepository;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * {@code youtube-new-video}: e-mails every user with channel e-mails on ({@code marketing_opt_in}, on by default)
 * about newly published videos. Runs as ONE task on the async pool; send-once per (video, user) through email_log
 * ref {@code youtube-new-video:<videoId>:<userId>}. At most {@code youtube.mail_daily_limit} of these e-mails go out
 * in any 24 hours (the mailbox quota is shared with sign-in codes and receipts); {@link YoutubeMailBacklogJob} sends
 * the rest later, oldest accounts first. Every e-mail carries a "stop these e-mails" link.
 */
@Slf4j
@Component
public class YoutubeVideoMailer {

    public static final String TEMPLATE = MailTemplates.YOUTUBE_NEW_VIDEO;
    static final int DEFAULT_DAILY_LIMIT = 300;

    private final UserRepository userRepository;
    private final EmailLogRepository emailLogRepository;
    private final MailService mailService;
    private final SettingsService settings;
    private final ChannelMailOptOut optOut;
    private final Clock clock;

    public YoutubeVideoMailer(UserRepository userRepository, EmailLogRepository emailLogRepository,
                              MailService mailService, SettingsService settings, ChannelMailOptOut optOut,
                              Clock clock) {
        this.userRepository = userRepository;
        this.emailLogRepository = emailLogRepository;
        this.mailService = mailService;
        this.settings = settings;
        this.optOut = optOut;
        this.clock = clock;
    }

    /** A video that just appeared in the feed. */
    public record NewVideo(String id, String title, String url, String thumbnailUrl) {
    }

    @Async
    public void announce(List<NewVideo> videos) {
        send(videos);
    }

    /** Same as {@link #announce} on the caller's thread (the backlog job). */
    public synchronized void send(List<NewVideo> videos) {
        if (videos == null || videos.isEmpty() || !mailService.isEnabled(TEMPLATE)) {
            return;
        }
        int limit = Math.max(0, settings.getInt(SettingKeys.YOUTUBE_MAIL_DAILY_LIMIT, DEFAULT_DAILY_LIMIT));
        long sent = emailLogRepository.countByTemplateAndCreatedAtAfter(TEMPLATE, clock.instant().minus(Duration.ofDays(1)));
        List<User> recipients = userRepository.findByRoleAndMarketingOptInTrueAndDeletedAtIsNullOrderByCreatedAtAsc(Role.USER);
        for (NewVideo video : videos) {
            for (User user : recipients) {
                if (sent >= limit) {
                    log.info("New-video e-mails: daily limit {} reached, the rest go out later", limit);
                    return;
                }
                String ref = TEMPLATE + ":" + video.id() + ":" + user.getId();
                try {
                    if (emailLogRepository.existsByTemplateAndRef(TEMPLATE, ref)) {
                        continue;
                    }
                    Map<String, Object> model = new LinkedHashMap<>();
                    model.put("name", user.getName());
                    model.put("videoTitle", video.title());
                    model.put("videoUrl", video.url());
                    model.put("thumbnailUrl", video.thumbnailUrl());
                    model.put("unsubscribeUrl", optOut.link(user.getId()));
                    mailService.send(user.getId(), user.getEmail(), TEMPLATE, user.getLocale(), model, ref);
                    sent++;
                } catch (RuntimeException e) {
                    log.warn("New-video e-mail for {} to user {} failed: {}", video.id(), user.getId(), e.toString());
                }
            }
        }
    }
}
