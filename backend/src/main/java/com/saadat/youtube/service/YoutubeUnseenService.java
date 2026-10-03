package com.saadat.youtube.service;

import com.saadat.users.domain.User;
import com.saadat.users.repo.UserRepository;
import com.saadat.youtube.api.YoutubeDtos.YoutubeUnseen;
import com.saadat.youtube.api.YoutubeDtos.YoutubeVideoDto;
import com.saadat.youtube.domain.YoutubeSeen;
import com.saadat.youtube.repo.YoutubeSeenRepository;
import com.saadat.youtube.repo.YoutubeVideoRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Per-user "new videos" badge on the YouTube button.
 * <ul>
 *   <li>{@code since} = when the user last pressed the button ({@code youtube_seen.seen_at}); a user who never pressed
 *       it counts from the day the account was created, so a new account does not start with the whole channel.</li>
 *   <li>{@code count} = videos published after {@code since} (the real number; the button shows "9+" above nine).</li>
 *   <li>{@code latest} = those videos, newest first (at most {@link #LIST_LIMIT}): one opens directly, several open
 *       a list.</li>
 * </ul>
 * Pressing the button calls {@link #markSeen}, which stores the current time.
 */
@Service
public class YoutubeUnseenService {

    /** Most videos returned in the list. */
    public static final int LIST_LIMIT = 20;

    private final YoutubeVideoRepository videoRepository;
    private final YoutubeSeenRepository seenRepository;
    private final UserRepository userRepository;
    private final Clock clock;

    public YoutubeUnseenService(YoutubeVideoRepository videoRepository, YoutubeSeenRepository seenRepository,
                                UserRepository userRepository, Clock clock) {
        this.videoRepository = videoRepository;
        this.seenRepository = seenRepository;
        this.userRepository = userRepository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public YoutubeUnseen unseen(UUID userId) {
        Instant since = since(userId);
        long count = videoRepository.countByPublishedAtAfter(since);
        if (count == 0) {
            return new YoutubeUnseen(0, java.util.List.of());
        }
        return new YoutubeUnseen(count, videoRepository.findByPublishedAtAfterOrderByPublishedAtDesc(since,
                org.springframework.data.domain.PageRequest.of(0, LIST_LIMIT)).stream().map(YoutubeVideoDto::from).toList());
    }

    @Transactional
    public void markSeen(UUID userId) {
        YoutubeSeen seen = seenRepository.findById(userId).orElseGet(() -> new YoutubeSeen(userId, clock.instant()));
        seen.setSeenAt(clock.instant());
        seenRepository.save(seen);
    }

    /** Last press of the button, else the account's creation, else now (unknown user: nothing is new). */
    Instant since(UUID userId) {
        return seenRepository.findById(userId).map(YoutubeSeen::getSeenAt)
                .or(() -> userRepository.findById(userId).map(User::getCreatedAt))
                .orElseGet(clock::instant);
    }
}
