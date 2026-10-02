package com.saadat.youtube.service;

import com.saadat.common.domain.Role;
import com.saadat.mail.MailService;
import com.saadat.mail.MailTemplates;
import com.saadat.notifications.repo.EmailLogRepository;
import com.saadat.users.domain.User;
import com.saadat.users.repo.UserRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * {@code youtube-new-video}: e-mails every user who opted in to news ({@code marketing_opt_in}) about newly
 * published videos. Runs as ONE task on the async pool per feed refresh; send-once per (video, user) through
 * email_log ref {@code youtube-new-video:<videoId>:<userId>}.
 */
@Slf4j
@Component
public class YoutubeVideoMailer {

    public static final String TEMPLATE = MailTemplates.YOUTUBE_NEW_VIDEO;

    private final UserRepository userRepository;
    private final EmailLogRepository emailLogRepository;
    private final MailService mailService;

    public YoutubeVideoMailer(UserRepository userRepository, EmailLogRepository emailLogRepository,
                              MailService mailService) {
        this.userRepository = userRepository;
        this.emailLogRepository = emailLogRepository;
        this.mailService = mailService;
    }

    /** A video that just appeared in the feed. */
    public record NewVideo(String id, String title, String url, String thumbnailUrl) {
    }

    @Async
    public void announce(List<NewVideo> videos) {
        if (videos == null || videos.isEmpty() || !mailService.isEnabled(TEMPLATE)) {
            return;
        }
        List<User> recipients = userRepository.findByRoleAndMarketingOptInTrueAndDeletedAtIsNull(Role.USER);
        for (NewVideo video : videos) {
            for (User user : recipients) {
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
                    mailService.send(user.getId(), user.getEmail(), TEMPLATE, user.getLocale(), model, ref);
                } catch (RuntimeException e) {
                    log.warn("New-video e-mail for {} to user {} failed: {}", video.id(), user.getId(), e.toString());
                }
            }
        }
    }
}
