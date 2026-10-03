package com.saadat.youtube;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.saadat.users.domain.User;
import com.saadat.users.repo.UserRepository;
import com.saadat.youtube.api.YoutubeDtos.YoutubeUnseen;
import com.saadat.youtube.api.YoutubeDtos.YoutubeVideoDto;
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
import org.springframework.data.domain.Pageable;

class YoutubeUnseenServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-01T12:00:00Z");

    private static final Instant JOINED = NOW.minusSeconds(30L * 24 * 3600);

    private YoutubeVideoRepository videos;
    private YoutubeSeenRepository seen;
    private UserRepository users;
    private YoutubeUnseenService service;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        videos = mock(YoutubeVideoRepository.class);
        seen = mock(YoutubeSeenRepository.class);
        users = mock(UserRepository.class);
        service = new YoutubeUnseenService(videos, seen, users, Clock.fixed(NOW, ZoneOffset.UTC));
        User user = new User();
        user.setCreatedAt(JOINED);
        when(users.findById(userId)).thenReturn(Optional.of(user));
    }

    @Test
    void neverPressedCountsFromTheAccountCreationNotTheWholeChannel() {
        when(seen.findById(userId)).thenReturn(Optional.empty());
        when(videos.countByPublishedAtAfter(JOINED)).thenReturn(2L);
        when(videos.findByPublishedAtAfterOrderByPublishedAtDesc(eq(JOINED), any(Pageable.class)))
                .thenReturn(List.of(video("b", NOW.minusSeconds(60)), video("a", NOW.minusSeconds(600))));

        YoutubeUnseen result = service.unseen(userId);

        assertThat(result.count()).isEqualTo(2);
        assertThat(result.latest()).extracting(YoutubeVideoDto::id).containsExactly("b", "a");
        verify(videos, never()).count();
    }

    @Test
    void nothingNewReturnsZeroAndAnEmptyList() {
        Instant seenAt = NOW.minusSeconds(3600);
        when(seen.findById(userId)).thenReturn(Optional.of(new YoutubeSeen(userId, seenAt)));
        when(videos.countByPublishedAtAfter(seenAt)).thenReturn(0L);

        YoutubeUnseen result = service.unseen(userId);

        assertThat(result.count()).isZero();
        assertThat(result.latest()).isEmpty();
        verify(videos, never()).findByPublishedAtAfterOrderByPublishedAtDesc(any(), any());
    }

    @Test
    void afterPressingOnlyNewerVideosCountAndTheRealNumberIsReturned() {
        Instant seenAt = NOW.minusSeconds(3600);
        when(seen.findById(userId)).thenReturn(Optional.of(new YoutubeSeen(userId, seenAt)));
        when(videos.countByPublishedAtAfter(seenAt)).thenReturn(12L);
        when(videos.findByPublishedAtAfterOrderByPublishedAtDesc(eq(seenAt), any(Pageable.class)))
                .thenReturn(List.of(video("x", NOW.minusSeconds(10))));

        assertThat(service.unseen(userId).count()).isEqualTo(12);
        verify(users, never()).findById(any());
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
