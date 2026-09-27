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
import java.util.List;
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
 * last fetch; nothing happens while {@code brand.youtube_channel_id} is empty.
 */
@Slf4j
@Service
public class YoutubeFeedService {

    static final long TICK_MINUTES = 5;
    static final String CHANNEL_PLACEHOLDER = "{channelId}";

    private static final int TITLE_MAX = 500;
    private static final int URL_MAX = 500;

    private final YoutubeVideoRepository repository;
    private final SettingsService settings;
    private final AppProperties.Youtube config;
    private final Clock clock;
    private final RestClient restClient;
    private final AtomicReference<Instant> lastFetchAt = new AtomicReference<>();

    public YoutubeFeedService(YoutubeVideoRepository repository, SettingsService settings, AppProperties properties,
                              Clock clock) {
        this.repository = repository;
        this.settings = settings;
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
        if (!isConfigured()) {
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

    void upsert(List<FeedEntry> entries) {
        Instant now = clock.instant();
        for (FeedEntry e : entries) {
            YoutubeVideo video = repository.findById(e.videoId()).orElseGet(() -> {
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
