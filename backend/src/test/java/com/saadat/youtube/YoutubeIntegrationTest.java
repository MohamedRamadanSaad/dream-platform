package com.saadat.youtube;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.saadat.IntegrationTestBase;
import com.saadat.common.api.ApiPaths;
import com.saadat.common.domain.Role;
import com.saadat.users.domain.User;
import com.saadat.youtube.domain.YoutubeVideo;
import com.saadat.youtube.repo.YoutubeVideoRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;

class YoutubeIntegrationTest extends IntegrationTestBase {

    @Autowired
    YoutubeVideoRepository videoRepository;

    @Test
    void unseenCountDropsAfterSeenAndCountsNewerVideos() throws Exception {
        Instant now = Instant.now();
        // the account is older than both videos: they count as new until the button is pressed
        User user = new User();
        user.setEmail("yt-" + java.util.UUID.randomUUID() + "@example.com");
        user.setName("yt");
        user.setRole(Role.USER);
        user.setOnboarded(true);
        user.setCountryCode("SA");
        user.setCreatedAt(now.minus(3, ChronoUnit.DAYS));
        user = userRepository.save(user);
        videoRepository.save(video("vidPast0001", now.minus(2, ChronoUnit.DAYS)));
        videoRepository.save(video("vidPast0002", now.minus(1, ChronoUnit.DAYS)));

        mvc.perform(get(ApiPaths.Youtube.UNSEEN).header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(2))
                .andExpect(jsonPath("$.latest.length()").value(2))
                .andExpect(jsonPath("$.latest[0].id").value("vidPast0002"))
                .andExpect(jsonPath("$.latest[0].url").isNotEmpty())
                .andExpect(jsonPath("$.latest[0].publishedAt").isNotEmpty());

        mvc.perform(post(ApiPaths.Youtube.SEEN).header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isNoContent());

        mvc.perform(get(ApiPaths.Youtube.UNSEEN).header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0))
                .andExpect(jsonPath("$.latest.length()").value(0));

        // a video published after the "seen" marker counts again
        videoRepository.save(video("vidFuture01", now.plus(1, ChronoUnit.HOURS)));
        mvc.perform(get(ApiPaths.Youtube.UNSEEN).header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.latest[0].id").value("vidFuture01"));
    }

    @Test
    void adminRefreshIsForbiddenForUsersAndNotConfiguredWithoutChannel() throws Exception {
        User user = createUser("yt-user", Role.USER);
        User interpreter = createUser("yt-admin", Role.INTERPRETER);

        mvc.perform(post(ApiPaths.Admin.YOUTUBE_REFRESH).header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isForbidden());
        // brand.youtube_channel_id is seeded empty
        mvc.perform(post(ApiPaths.Admin.YOUTUBE_REFRESH).header(HttpHeaders.AUTHORIZATION, bearer(interpreter)))
                .andExpect(status().isServiceUnavailable());
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

