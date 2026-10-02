package com.saadat.youtube.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.saadat.config.props.AppProperties;
import com.saadat.settings.SettingKeys;
import com.saadat.settings.SettingsService;
import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** brand.youtube_channel_id is filled from the handle URL once, through the audited settings path. */
class YoutubeChannelIdResolverTest {

    private static final String HANDLE_URL = "https://youtube.com/@almoaberafatema";
    private static final String OWN = YoutubeChannelPageParserTest.OWN;

    private final SettingsService settings = mock(SettingsService.class);
    private final AppProperties properties = new AppProperties();
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-02T10:00:00Z"));
    private final List<URI> fetched = new ArrayList<>();
    private String page = YoutubeChannelPageParserTest.CHANNEL_PAGE;
    private IOException failure;

    private final YoutubeChannelIdResolver resolver = new YoutubeChannelIdResolver(settings, properties, clock, uri -> {
        fetched.add(uri);
        if (failure != null) {
            throw failure;
        }
        return page;
    });

    @BeforeEach
    void setUp() {
        when(settings.getString(SettingKeys.BRAND_YOUTUBE_CHANNEL_ID, "")).thenReturn("");
        when(settings.getString(SettingKeys.BRAND_YOUTUBE_URL, "")).thenReturn(HANDLE_URL);
        when(settings.getInt(SettingKeys.YOUTUBE_POLL_MINUTES)).thenReturn(30);
    }

    @Test
    void storesTheIdOfTheChannelPageThroughTheAuditedSettingsPath() {
        assertThat(resolver.resolveIfDue()).contains(OWN);

        assertThat(fetched).containsExactly(URI.create(HANDLE_URL));
        verify(settings).put(SettingKeys.BRAND_YOUTUBE_CHANNEL_ID, OWN, null);
    }

    @Test
    void doesNothingWhenTheIdIsAlreadySetOrResolvingIsOff() {
        when(settings.getString(SettingKeys.BRAND_YOUTUBE_CHANNEL_ID, "")).thenReturn("UC_-aB3dE5fG7hI9jK1lM3nO");
        assertThat(resolver.resolveIfDue()).isEmpty();

        when(settings.getString(SettingKeys.BRAND_YOUTUBE_CHANNEL_ID, "")).thenReturn("");
        properties.getYoutube().setResolveChannelId(false);
        assertThat(resolver.resolveIfDue()).isEmpty();

        assertThat(fetched).isEmpty();
        verify(settings, never()).put(anyString(), any(), any());
    }

    @Test
    void aFailureIsRetriedOnTheNextPollOnly() {
        failure = new IOException("connect timed out");
        assertThat(resolver.resolveIfDue()).isEmpty();

        clock.advance(Duration.ofMinutes(29));
        assertThat(resolver.resolveIfDue()).as("before youtube.poll_minutes").isEmpty();
        assertThat(fetched).hasSize(1);

        failure = null;
        clock.advance(Duration.ofMinutes(1));
        assertThat(resolver.resolveIfDue()).contains(OWN);
        assertThat(fetched).hasSize(2);
        verify(settings).put(SettingKeys.BRAND_YOUTUBE_CHANNEL_ID, OWN, null);
    }

    @Test
    void aPageWithoutAChannelIdStoresNothing() {
        page = "<html><title>Before you continue to YouTube</title></html>";

        assertThat(resolver.resolveIfDue()).isEmpty();
        assertThat(fetched).hasSize(1);
        verify(settings, never()).put(anyString(), any(), any());
    }

    @Test
    void aChannelUrlNeedsNoFetch() {
        when(settings.getString(SettingKeys.BRAND_YOUTUBE_URL, ""))
                .thenReturn("https://www.youtube.com/channel/" + OWN);

        assertThat(resolver.resolveIfDue()).contains(OWN);
        assertThat(fetched).isEmpty();
        verify(settings).put(eq(SettingKeys.BRAND_YOUTUBE_CHANNEL_ID), eq(OWN), isNull());
    }

    @Test
    void onlyHttpsPagesOnTheConfiguredDomainAreFetched() {
        for (String url : List.of("http://youtube.com/@almoaberafatema", "https://youtube.com.evil.example/@x",
                "https://notyoutube.com/@x", "https://127.0.0.1/@x", "ftp://youtube.com/@x", "youtube.com/@x", "")) {
            when(settings.getString(SettingKeys.BRAND_YOUTUBE_URL, "")).thenReturn(url);
            clock.advance(Duration.ofHours(1));
            assertThat(resolver.resolveIfDue()).as(url).isEmpty();
        }
        assertThat(fetched).isEmpty();
        verify(settings, never()).put(anyString(), any(), any());

        assertThat(resolver.allowedPage("https://www.youtube.com/@x")).isNotNull();
        assertThat(resolver.allowedPage("https://M.YouTube.com/@x")).isNotNull();
        assertThat(resolver.allowedPage(URI.create("https://consent.youtube.com/m?continue=x"))).isNotNull();
        assertThat(resolver.allowedPage(URI.create("https://accounts.google.com/x"))).isNull();
    }

    @Test
    void neverThrows() {
        when(settings.getInt(SettingKeys.YOUTUBE_POLL_MINUTES)).thenThrow(new IllegalStateException("broken"));
        assertThat(resolver.resolveIfDue()).isEqualTo(Optional.empty());
    }

    /** A clock the test moves forward. */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
