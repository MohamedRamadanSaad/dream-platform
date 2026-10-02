package com.saadat.youtube.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.saadat.IntegrationTestBase;
import com.saadat.common.domain.Role;
import com.saadat.mail.MailTemplates;
import com.saadat.notifications.repo.EmailLogRepository;
import com.saadat.users.domain.User;
import com.saadat.youtube.domain.YoutubeVideo;
import com.saadat.youtube.repo.YoutubeVideoRepository;
import com.saadat.youtube.service.YoutubeFeedParser.FeedEntry;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** youtube-new-video is sent to users who opted in, only for videos that are new and recent. */
class YoutubeNewVideoMailIntegrationTest extends IntegrationTestBase {

    @Autowired
    YoutubeFeedService feedService;

    @Autowired
    YoutubeVideoRepository videoRepository;

    @Autowired
    EmailLogRepository emailLogRepository;

    @Test
    void aNewVideoIsMailedToOptedInUsersOnly() {
        videoRepository.save(video("seed" + shortId(), Instant.now().minus(10, ChronoUnit.DAYS)));
        User optedIn = createUser("yt-optin", Role.USER);
        optedIn.setMarketingOptIn(true);
        optedIn = userRepository.save(optedIn);
        User optedOut = createUser("yt-optout", Role.USER);

        String fresh = "new" + shortId();
        String old = "old" + shortId();
        feedService.upsert(List.of(
                new FeedEntry(fresh, "A new video", Instant.now().minus(1, ChronoUnit.HOURS),
                        "https://www.youtube.com/watch?v=" + fresh, null),
                new FeedEntry(old, "An old video", Instant.now().minus(30, ChronoUnit.DAYS),
                        "https://www.youtube.com/watch?v=" + old, null)));

        String template = MailTemplates.YOUTUBE_NEW_VIDEO;
        String ref = template + ":" + fresh + ":" + optedIn.getId();
        awaitTrue("youtube-new-video e-mail", () -> emailLogRepository.existsByTemplateAndRef(template, ref));
        assertThat(emailLogRepository.existsByTemplateAndRef(template, template + ":" + fresh + ":" + optedOut.getId()))
                .isFalse();
        assertThat(emailLogRepository.existsByTemplateAndRef(template, template + ":" + old + ":" + optedIn.getId()))
                .as("old videos are never announced").isFalse();
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static YoutubeVideo video(String id, Instant publishedAt) {
        YoutubeVideo v = new YoutubeVideo();
        v.setId(id);
        v.setTitle("title " + id);
        v.setUrl("https://www.youtube.com/watch?v=" + id);
        v.setPublishedAt(publishedAt);
        v.setFetchedAt(Instant.now());
        return v;
    }
}
