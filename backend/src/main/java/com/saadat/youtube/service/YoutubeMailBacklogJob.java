package com.saadat.youtube.service;

import com.saadat.youtube.repo.YoutubeVideoRepository;
import java.time.Clock;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Every hour: continues new-video announcements that stopped at the daily limit. Only videos still inside the
 * announce window and already announced to someone (so a first import never e-mails old videos).
 */
@Slf4j
@Component
public class YoutubeMailBacklogJob {

    private final YoutubeVideoRepository videoRepository;
    private final com.saadat.notifications.repo.EmailLogRepository emailLogRepository;
    private final YoutubeVideoMailer mailer;
    private final Clock clock;

    public YoutubeMailBacklogJob(YoutubeVideoRepository videoRepository,
                                 com.saadat.notifications.repo.EmailLogRepository emailLogRepository,
                                 YoutubeVideoMailer mailer, Clock clock) {
        this.videoRepository = videoRepository;
        this.emailLogRepository = emailLogRepository;
        this.mailer = mailer;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT10M")
    public void run() {
        try {
            List<YoutubeVideoMailer.NewVideo> pending = videoRepository
                    .findByPublishedAtAfterOrderByPublishedAtDesc(clock.instant().minus(YoutubeFeedService.ANNOUNCE_WINDOW),
                            org.springframework.data.domain.PageRequest.of(0, 20))
                    .stream()
                    .filter(v -> emailLogRepository.existsByTemplateAndRefStartingWith(YoutubeVideoMailer.TEMPLATE,
                            YoutubeVideoMailer.TEMPLATE + ":" + v.getId() + ":"))
                    .map(v -> new YoutubeVideoMailer.NewVideo(v.getId(), v.getTitle(), v.getUrl(), v.getThumbnailUrl()))
                    .toList();
            mailer.send(pending);
        } catch (RuntimeException e) {
            log.error("New-video e-mail backlog failed", e);
        }
    }
}
