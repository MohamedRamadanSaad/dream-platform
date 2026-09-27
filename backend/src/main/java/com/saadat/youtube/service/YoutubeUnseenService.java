package com.saadat.youtube.service;

import com.saadat.youtube.api.YoutubeDtos.YoutubeUnseen;
import com.saadat.youtube.api.YoutubeDtos.YoutubeVideoDto;
import com.saadat.youtube.domain.YoutubeSeen;
import com.saadat.youtube.repo.YoutubeSeenRepository;
import com.saadat.youtube.repo.YoutubeVideoRepository;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Per-user "new videos" badge: count = videos published after the user's {@code youtube_seen.seen_at}
 * (all videos when the user never opened it), capped at {@link #COUNT_CAP}; {@code latest} = the newest
 * five videos regardless of seen state.
 */
@Service
public class YoutubeUnseenService {

    /** The badge shows at most "9+". */
    public static final long COUNT_CAP = 9;

    private final YoutubeVideoRepository videoRepository;
    private final YoutubeSeenRepository seenRepository;
    private final Clock clock;

    public YoutubeUnseenService(YoutubeVideoRepository videoRepository, YoutubeSeenRepository seenRepository,
                                Clock clock) {
        this.videoRepository = videoRepository;
        this.seenRepository = seenRepository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public YoutubeUnseen unseen(UUID userId) {
        Optional<YoutubeSeen> seen = seenRepository.findById(userId);
        long count = seen.isPresent()
                ? videoRepository.countByPublishedAtAfter(seen.get().getSeenAt())
                : videoRepository.count();
        return new YoutubeUnseen(Math.min(count, COUNT_CAP),
                videoRepository.findTop5ByOrderByPublishedAtDesc().stream().map(YoutubeVideoDto::from).toList());
    }

    @Transactional
    public void markSeen(UUID userId) {
        YoutubeSeen seen = seenRepository.findById(userId).orElseGet(() -> new YoutubeSeen(userId, clock.instant()));
        seen.setSeenAt(clock.instant());
        seenRepository.save(seen);
    }
}
