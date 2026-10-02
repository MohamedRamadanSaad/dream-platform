package com.saadat.youtube.service;

import com.saadat.config.props.AppProperties;
import com.saadat.settings.SettingKeys;
import com.saadat.settings.SettingsService;
import com.saadat.youtube.domain.YoutubeVideo;
import com.saadat.youtube.repo.YoutubeVideoRepository;
import com.saadat.youtube.service.YoutubeFeedParser.FeedEntry;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * Polls the channel's public Atom feed and upserts {@code youtube_videos}. The scheduler ticks every
 * {@link #TICK_MINUTES} minutes and fetches only when setting {@code youtube.poll_minutes} has elapsed since the
 * last fetch. While {@code brand.youtube_channel_id} is empty, the id is first resolved from the channel URL
 * ({@link YoutubeChannelIdResolver}); nothing is fetched until it is known.
 */
@Slf4j
@Service
public class YoutubeFeedService {

    static final long TICK_MINUTES = 5;
    static final String CHANNEL_PLACEHOLDER = "{channelId}";
    /** Only videos published this recently are announced by e-mail (never the back catalogue). */
    static final Duration ANNOUNCE_WINDOW = Duration.ofDays(3);

    private static final int TITLE_MAX = 500;
    private static final int URL_MAX = 500;

    private final YoutubeVideoRepository repository;
    private final SettingsService settings;
    private final YoutubeVideoMailer videoMailer;
    private final YoutubeChannelIdResolver channelIdResolver;
    private final AppProperties.Youtube config;
    private final Clock clock;
    private final RestClient restClient;
    private final AtomicReference<Instant> lastFetchAt = new AtomicReference<>();

    public YoutubeFeedService(YoutubeVideoRepository repository, SettingsService settings,
                              YoutubeVideoMailer videoMailer, YoutubeChannelIdResolver channelIdResolver,
                              AppProperties properties, Clock clock) {
        this.repository = repository;
        this.settings = settings;
        this.videoMailer = videoMailer;
        this.channelIdResolver = channelIdResolver;
        this.config = properties.getYoutube();
        this.clock = clock;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        int timeoutMillis = (int) Duration.ofSeconds(config.getTimeoutSeconds()).toMillis();
        factory.setConnectTimeout(timeoutMillis);
        factory.setReadTimeout(timeoutMillis);
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    public boolean isConfigured() {
        return !channelId().isEmpty();
    }

    @Scheduled(initialDelay = 1, fixedDelay = TICK_MINUTES, timeUnit = TimeUnit.MINUTES)
    public void tick() {
        if (!isConfigured() && channelIdResolver.resolveIfDue().isEmpty()) {
            return;
        }
        Instant last = lastFetchAt.get();
        Duration interval = Duration.ofMinutes(settings.getInt(SettingKeys.YOUTUBE_POLL_MINUTES));
        if (last != null && last.plus(interval).isAfter(clock.instant())) {
            return;
        }
        try {
            refresh();
        } catch (RuntimeException e) {
            log.warn("YouTube feed poll failed: {}", e.toString());
        }
    }

    /** Fetches and upserts now; returns the number of entries in the feed (0 when not configured). */
    public int refresh() {
        String channelId = channelId();
        if (channelId.isEmpty()) {
            return 0;
        }
        String url = config.getFeedUrl().replace(CHANNEL_PLACEHOLDER,
                URLEncoder.encode(channelId, StandardCharsets.UTF_8));
        byte[] xml = restClient.get().uri(URI.create(url)).retrieve().body(byte[].class);
        lastFetchAt.set(clock.instant());
        List<FeedEntry> entries = YoutubeFeedParser.parse(xml);
        upsert(entries);
        log.debug("YouTube feed refreshed: {} entries", entries.size());
        return entries.size();
    }

    /**
     * Upserts the feed entries. Videos that are new to the table, recently published and not part of the very first
     * import (an empty table = back catalogue) are announced to opted-in users (youtube-new-video).
     */
    void upsert(List<FeedEntry> entries) {
        Instant now = clock.instant();
        boolean firstImport = repository.count() == 0;
        List<YoutubeVideoMailer.NewVideo> fresh = new ArrayList<>();
        for (FeedEntry e : entries) {
            Optional<YoutubeVideo> existing = repository.findById(e.videoId());
            YoutubeVideo video = existing.orElseGet(() -> {
                YoutubeVideo v = new YoutubeVideo();
                v.setId(e.videoId());
                return v;
            });
            video.setTitle(truncate(e.title(), TITLE_MAX));
            video.setPublishedAt(e.publishedAt());
            video.setUrl(truncate(e.url(), URL_MAX));
            video.setThumbnailUrl(truncate(e.thumbnailUrl(), URL_MAX));
            video.setFetchedAt(now);
            repository.save(video);
            if (existing.isEmpty() && !firstImport && e.publishedAt() != null
                    && e.publishedAt().isAfter(now.minus(ANNOUNCE_WINDOW))) {
                fresh.add(new YoutubeVideoMailer.NewVideo(video.getId(), video.getTitle(), video.getUrl(),
                        video.getThumbnailUrl()));
            }
        }
        if (!fresh.isEmpty()) {
            videoMailer.announce(fresh);
        }
    }

    private String channelId() {
        String id = settings.getString(SettingKeys.BRAND_YOUTUBE_CHANNEL_ID, "");
        return id == null ? "" : id.trim();
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() > max ? value.substring(0, max) : value;
    }
}
