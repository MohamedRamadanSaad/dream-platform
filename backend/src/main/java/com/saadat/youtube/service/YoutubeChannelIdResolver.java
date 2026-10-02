package com.saadat.youtube.service;

import com.saadat.config.props.AppProperties;
import com.saadat.settings.SettingKeys;
import com.saadat.settings.SettingsService;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Fills setting {@code brand.youtube_channel_id} when it is empty, so the feed poller works from the channel's handle
 * URL alone: fetches the page in setting {@code brand.youtube_url} (e.g. {@code https://youtube.com/@name}) and stores
 * the {@code UC…} id the page declares for itself ({@link YoutubeChannelPageParser}). At most one attempt per
 * {@code youtube.poll_minutes}; a failure is logged and retried on the next poll.
 *
 * <p>Only https pages on {@code app.youtube.channel-page-domain} (or a subdomain) are fetched, redirects included.
 * The id is written through {@link SettingsService#put}: validated and audited like a dashboard change, with no
 * actor (system). Off when {@code app.youtube.resolve-channel-id=false} (tests).
 */
@Slf4j
@Component
public class YoutubeChannelIdResolver {

    static final int MAX_REDIRECTS = 5;

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";
    /** Answers YouTube's cookie-consent interstitial (served to some regions) so the channel page itself comes back. */
    private static final String CONSENT_COOKIE = "SOCS=CAI";

    private final SettingsService settings;
    private final AppProperties.Youtube config;
    private final Clock clock;
    private final PageFetcher fetcher;
    private final AtomicReference<Instant> lastAttemptAt = new AtomicReference<>();
    private volatile HttpClient httpClient;

    @Autowired
    public YoutubeChannelIdResolver(SettingsService settings, AppProperties properties, Clock clock) {
        this(settings, properties, clock, null);
    }

    /** {@code fetcher} = null → real HTTPS download. */
    YoutubeChannelIdResolver(SettingsService settings, AppProperties properties, Clock clock, PageFetcher fetcher) {
        this.settings = settings;
        this.config = properties.getYoutube();
        this.clock = clock;
        this.fetcher = fetcher != null ? fetcher : this::download;
    }

    /**
     * When the channel id is empty and the last attempt is at least {@code youtube.poll_minutes} old: resolves it
     * from the channel URL and stores it. Returns the stored id; empty when nothing was stored. Never throws.
     */
    public Optional<String> resolveIfDue() {
        if (!config.isResolveChannelId()) {
            return Optional.empty();
        }
        URI page = null;
        try {
            if (!currentId().isEmpty()) {
                return Optional.empty();
            }
            Instant now = clock.instant();
            Instant last = lastAttemptAt.get();
            Duration interval = Duration.ofMinutes(settings.getInt(SettingKeys.YOUTUBE_POLL_MINUTES));
            if (last != null && last.plus(interval).isAfter(now)) {
                return Optional.empty();
            }
            lastAttemptAt.set(now);

            String pageUrl = settings.getString(SettingKeys.BRAND_YOUTUBE_URL, "");
            page = allowedPage(pageUrl == null ? "" : pageUrl.trim());
            if (page == null) {
                log.warn("YouTube channel id not resolved: setting {} ({}) is not an https page on {}",
                        SettingKeys.BRAND_YOUTUBE_URL, pageUrl, config.getChannelPageDomain());
                return Optional.empty();
            }
            Optional<String> id = YoutubeChannelPageParser.channelIdInUrl(page);
            if (id.isEmpty()) {
                id = YoutubeChannelPageParser.channelId(fetcher.fetch(page));
            }
            if (id.isEmpty()) {
                log.warn("YouTube channel id not found on {}; retrying on the next poll", page);
                return Optional.empty();
            }
            if (!currentId().isEmpty()) {
                return Optional.empty();                    // saved from the dashboard meanwhile
            }
            settings.put(SettingKeys.BRAND_YOUTUBE_CHANNEL_ID, id.get(), null);
            log.info("YouTube channel id {} resolved from {} and saved to setting {}", id.get(), page,
                    SettingKeys.BRAND_YOUTUBE_CHANNEL_ID);
            return id;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            log.warn("YouTube channel id not resolved from {}: {}; retrying on the next poll", page, e.toString());
            return Optional.empty();
        }
    }

    /** The URL when it is https on the configured domain (or a subdomain), else null. */
    URI allowedPage(String url) {
        if (url == null || url.isEmpty()) {
            return null;
        }
        try {
            return allowedPage(new URI(url));
        } catch (URISyntaxException e) {
            return null;
        }
    }

    URI allowedPage(URI uri) {
        String domain = config.getChannelPageDomain() == null ? ""
                : config.getChannelPageDomain().trim().toLowerCase(Locale.ROOT);
        String host = uri.getHost();
        if (domain.isEmpty() || host == null || !"https".equalsIgnoreCase(uri.getScheme())) {
            return null;
        }
        String h = host.toLowerCase(Locale.ROOT);
        return h.equals(domain) || h.endsWith("." + domain) ? uri : null;
    }

    private String currentId() {
        String id = settings.getString(SettingKeys.BRAND_YOUTUBE_CHANNEL_ID, "");
        return id == null ? "" : id.trim();
    }

    /** GET with manual redirects, so every hop is checked against the allowed domain. */
    private String download(URI start) throws IOException, InterruptedException {
        Duration timeout = Duration.ofSeconds(config.getTimeoutSeconds());
        URI uri = start;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(timeout)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept-Language", "en")
                    .header("Cookie", CONSENT_COOKIE)
                    .GET()
                    .build();
            HttpResponse<byte[]> response = client(timeout).send(request, HttpResponse.BodyHandlers.ofByteArray());
            int status = response.statusCode();
            if (status >= 300 && status < 400) {
                String location = response.headers().firstValue("Location")
                        .orElseThrow(() -> new IOException("HTTP " + status + " without Location"));
                URI next = uri.resolve(location);
                if (allowedPage(next) == null) {
                    throw new IOException("redirected outside " + config.getChannelPageDomain() + ": " + next.getHost());
                }
                uri = next;
                continue;
            }
            if (status != 200) {
                throw new IOException("HTTP " + status + " from " + uri);
            }
            return new String(response.body(), StandardCharsets.UTF_8);
        }
        throw new IOException("more than " + MAX_REDIRECTS + " redirects from " + start);
    }

    private HttpClient client(Duration timeout) {
        HttpClient current = httpClient;
        if (current == null) {
            synchronized (this) {
                current = httpClient;
                if (current == null) {
                    current = HttpClient.newBuilder()
                            .connectTimeout(timeout)
                            .followRedirects(HttpClient.Redirect.NEVER)
                            .build();
                    httpClient = current;
                }
            }
        }
        return current;
    }

    /** Downloads a page as text. */
    @FunctionalInterface
    interface PageFetcher {
        String fetch(URI page) throws IOException, InterruptedException;
    }
}
