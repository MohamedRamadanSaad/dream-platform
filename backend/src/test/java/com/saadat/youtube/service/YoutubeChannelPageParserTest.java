package com.saadat.youtube.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.junit.jupiter.api.Test;

/** Channel id extraction from channel-page HTML fixtures (excerpts in the shape youtube.com/@handle serves). */
class YoutubeChannelPageParserTest {

    static final String OWN = "UCx3Fz8NQe2kWb5RtL7yVa0J";
    static final String OTHER = "UCq9Lm2Hd4Ts6Vw8Xy0Zb1Nc";

    /** Head + page data of a channel page; another channel appears first in the data (featured channel). */
    static final String CHANNEL_PAGE = """
            <!DOCTYPE html><html style="font-size: 10px;font-family: Roboto, Arial, sans-serif;" lang="en" \
            system-icons typography><head><script nonce="n1">var ytcfg={d:function(){return window.yt}};</script>
            <title>المعبرة فاطمة - YouTube</title><meta name="title" content="المعبرة فاطمة">
            <link rel="shortlink" href="https://youtu.be/">
            <link rel="canonical" href="https://www.youtube.com/channel/%1$s">
            <link rel="alternate" media="handheld" href="https://m.youtube.com/@almoaberafatema">
            <link rel="alternate" type="application/rss+xml" title="RSS" \
            href="https://www.youtube.com/feeds/videos.xml?channel_id=%1$s">
            <meta property="og:site_name" content="YouTube">
            <meta property="og:url" content="https://www.youtube.com/channel/%1$s">
            </head><body><script nonce="n1">var ytInitialData = {"responseContext":{"serviceTrackingParams":[]},\
            "contents":{"sectionListRenderer":{"contents":[{"channelRenderer":{"channelId":"%2$s",\
            "title":{"simpleText":"قناة أخرى"}}}]}},"metadata":{"channelMetadataRenderer":{"title":"المعبرة فاطمة",\
            "rssUrl":"https://www.youtube.com/feeds/videos.xml?channel_id=%1$s","externalId":"%1$s",\
            "vanityChannelUrl":"http://www.youtube.com/@almoaberafatema"}}};</script></body></html>
            """.formatted(OWN, OTHER);

    @Test
    void canonicalLinkWinsOverOtherChannelsOnThePage() {
        assertThat(YoutubeChannelPageParser.channelId(CHANNEL_PAGE)).contains(OWN);
    }

    @Test
    void canonicalLinkWithAttributesInAnyOrder() {
        String html = "<head><link href=\"https://www.youtube.com/channel/" + OWN + "\" rel='canonical'></head>";
        assertThat(YoutubeChannelPageParser.channelId(html)).contains(OWN);
    }

    @Test
    void fallsBackToExternalIdWhenTheCanonicalLinkIsTheHandle() {
        String html = """
                <link rel="canonical" href="https://www.youtube.com/@almoaberafatema">
                <script>var ytInitialData = {"x":{"channelId":"%2$s"},"metadata":{"channelMetadataRenderer":\
                {"externalId":"%1$s"}}};</script>
                """.formatted(OWN, OTHER);
        assertThat(YoutubeChannelPageParser.channelId(html)).contains(OWN);
    }

    @Test
    void fallsBackToMetaTagsAndTheRssLink() {
        assertThat(YoutubeChannelPageParser.channelId("<meta itemprop=\"identifier\" content=\"" + OWN + "\">"))
                .contains(OWN);
        assertThat(YoutubeChannelPageParser.channelId("<meta itemprop=\"channelId\" content=\"" + OWN + "\">"))
                .contains(OWN);
        assertThat(YoutubeChannelPageParser.channelId("<link rel=\"alternate\" type=\"application/rss+xml\" "
                + "href=\"https://www.youtube.com/feeds/videos.xml?channel_id=" + OWN + "\">")).contains(OWN);
        assertThat(YoutubeChannelPageParser.channelId("<meta property=\"og:url\" "
                + "content=\"https://www.youtube.com/channel/" + OWN + "\">")).contains(OWN);
    }

    @Test
    void channelIdValuesCountOnlyWhenTheyAllAgree() {
        String same = "{\"a\":{\"channelId\":\"%1$s\"},\"b\":{\"channelId\": \"%1$s\"}}".formatted(OWN);
        assertThat(YoutubeChannelPageParser.channelId(same)).contains(OWN);

        String mixed = "{\"a\":{\"channelId\":\"%1$s\"},\"b\":{\"channelId\":\"%2$s\"}}".formatted(OWN, OTHER);
        assertThat(YoutubeChannelPageParser.channelId(mixed)).isEmpty();
    }

    @Test
    void pagesWithoutAValidIdGiveEmpty() {
        String consent = "<html><head><title>Before you continue to YouTube</title></head>"
                + "<form action=\"https://consent.youtube.com/save\"></form></html>";
        assertThat(YoutubeChannelPageParser.channelId(consent)).isEmpty();
        assertThat(YoutubeChannelPageParser.channelId("")).isEmpty();
        assertThat(YoutubeChannelPageParser.channelId(null)).isEmpty();
        assertThat(YoutubeChannelPageParser.channelId("{\"externalId\":\"UCtooShort\"}")).isEmpty();
        assertThat(YoutubeChannelPageParser.channelId("{\"externalId\":\"" + OWN + "X\"}"))
                .as("25 characters is not a channel id").isEmpty();
        assertThat(YoutubeChannelPageParser.channelId(
                "<link rel=\"canonical\" href=\"https://www.youtube.com/channel/" + OWN + "Z\">")).isEmpty();
    }

    @Test
    void readsTheIdFromAChannelUrl() {
        assertThat(YoutubeChannelPageParser.channelIdInUrl(URI.create("https://www.youtube.com/channel/" + OWN)))
                .contains(OWN);
        assertThat(YoutubeChannelPageParser.channelIdInUrl(
                URI.create("https://youtube.com/channel/" + OWN + "/videos?view=0"))).contains(OWN);
        assertThat(YoutubeChannelPageParser.channelIdInUrl(URI.create("https://youtube.com/@almoaberafatema")))
                .isEmpty();
        assertThat(YoutubeChannelPageParser.channelIdInUrl(null)).isEmpty();
    }

    @Test
    void validatesTheIdFormat() {
        assertThat(YoutubeChannelPageParser.isChannelId(OWN)).isTrue();
        assertThat(YoutubeChannelPageParser.isChannelId("UC_-aB3dE5fG7hI9jK1lM3nO")).isTrue();
        assertThat(YoutubeChannelPageParser.isChannelId("UU" + OWN.substring(2))).isFalse();
        assertThat(YoutubeChannelPageParser.isChannelId(OWN.substring(0, 23))).isFalse();
        assertThat(YoutubeChannelPageParser.isChannelId(OWN + "A")).isFalse();
        assertThat(YoutubeChannelPageParser.isChannelId(null)).isFalse();
    }
}
