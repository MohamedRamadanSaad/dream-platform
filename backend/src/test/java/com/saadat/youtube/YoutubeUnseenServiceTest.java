package com.saadat.youtube;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.saadat.youtube.api.YoutubeDtos.YoutubeUnseen;
import com.saadat.youtube.domain.YoutubeSeen;
import com.saadat.youtube.domain.YoutubeVideo;
import com.saadat.youtube.repo.YoutubeSeenRepository;
import com.saadat.youtube.repo.YoutubeVideoRepository;
import com.saadat.youtube.service.YoutubeUnseenService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class YoutubeUnseenServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-01T12:00:00Z");

    private YoutubeVideoRepository videos;
    private YoutubeSeenRepository seen;
    private YoutubeUnseenService service;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        videos = mock(YoutubeVideoRepository.class);
        seen = mock(YoutubeSeenRepository.class);
        service = new YoutubeUnseenService(videos, seen, Clock.fixed(NOW, ZoneOffset.UTC));
        when(videos.findTop5ByOrderByPublishedAtDesc()).thenReturn(List.of(video("abc", NOW.minusSeconds(60))));
    }

    @Test
    void neverSeenCountsAllVideos() {
        when(seen.findById(userId)).thenReturn(Optional.empty());
        when(videos.count()).thenReturn(4L);

        YoutubeUnseen result = service.unseen(userId);

        assertThat(result.count()).isEqualTo(4);
        assertThat(result.latest()).singleElement().satisfies(v -> assertThat(v.id()).isEqualTo("abc"));
        verify(videos, never()).countByPublishedAtAfter(any());
    }

    @Test
    void neverSeenCountIsCappedAtNine() {
        when(seen.findById(userId)).thenReturn(Optional.empty());
        when(videos.count()).thenReturn(230L);

        assertThat(service.unseen(userId).count()).isEqualTo(YoutubeUnseenService.COUNT_CAP);
    }

    @Test
    void seenCountsOnlyNewerVideos() {
        Instant seenAt = NOW.minusSeconds(3600);
        when(seen.findById(userId)).thenReturn(Optional.of(new YoutubeSeen(userId, seenAt)));
        when(videos.countByPublishedAtAfter(seenAt)).thenReturn(2L);

        assertThat(service.unseen(userId).count()).isEqualTo(2);
        verify(videos, never()).count();
    }

    @Test
    void seenWithManyNewerVideosIsCapped() {
        Instant seenAt = NOW.minusSeconds(3600);
        when(seen.findById(userId)).thenReturn(Optional.of(new YoutubeSeen(userId, seenAt)));
        when(videos.countByPublishedAtAfter(seenAt)).thenReturn(12L);

        assertThat(service.unseen(userId).count()).isEqualTo(9);
    }

    @Test
    void markSeenStoresNow() {
        when(seen.findById(userId)).thenReturn(Optional.of(new YoutubeSeen(userId, NOW.minusSeconds(999))));

        service.markSeen(userId);

        ArgumentCaptor<YoutubeSeen> captor = ArgumentCaptor.forClass(YoutubeSeen.class);
        verify(seen).save(captor.capture());
        assertThat(captor.getValue().getSeenAt()).isEqualTo(NOW);
    }

    private static YoutubeVideo video(String id, Instant publishedAt) {
        YoutubeVideo v = new YoutubeVideo();
        v.setId(id);
        v.setTitle("t-" + id);
        v.setUrl("https://www.youtube.com/watch?v=" + id);
        v.setPublishedAt(publishedAt);
        v.setFetchedAt(publishedAt);
        return v;
    }
}
