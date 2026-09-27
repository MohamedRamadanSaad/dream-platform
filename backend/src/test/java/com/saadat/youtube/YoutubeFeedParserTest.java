package com.saadat.youtube;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.saadat.youtube.service.YoutubeFeedParser;
import com.saadat.youtube.service.YoutubeFeedParser.FeedEntry;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class YoutubeFeedParserTest {

    private static final String FEED = """
            <?xml version="1.0" encoding="UTF-8"?>
            <feed xmlns:yt="http://www.youtube.com/xml/schemas/2015"
                  xmlns:media="http://search.yahoo.com/mrss/"
                  xmlns="http://www.w3.org/2005/Atom">
              <title>Channel</title>
              <entry>
                <id>yt:video:AAAAAAAAAAA</id>
                <yt:videoId>AAAAAAAAAAA</yt:videoId>
                <yt:channelId>UCxxxx</yt:channelId>
                <title>تفسير رؤيا الماء</title>
                <link rel="alternate" href="https://www.youtube.com/watch?v=AAAAAAAAAAA"/>
                <published>2026-09-20T18:00:00+00:00</published>
                <updated>2026-09-21T10:00:00+00:00</updated>
                <media:group>
                  <media:title>تفسير رؤيا الماء</media:title>
                  <media:thumbnail url="https://i1.ytimg.com/vi/AAAAAAAAAAA/hqdefault.jpg" width="480" height="360"/>
                </media:group>
              </entry>
              <entry>
                <yt:videoId>BBBBBBBBBBB</yt:videoId>
                <title>Second</title>
                <published>2026-09-10T08:30:00+03:00</published>
              </entry>
              <entry>
                <title>No id — skipped</title>
                <published>2026-09-10T08:30:00+00:00</published>
              </entry>
            </feed>
            """;

    @Test
    void parsesEntries() {
        List<FeedEntry> entries = YoutubeFeedParser.parse(FEED.getBytes(StandardCharsets.UTF_8));

        assertThat(entries).hasSize(2);
        FeedEntry first = entries.get(0);
        assertThat(first.videoId()).isEqualTo("AAAAAAAAAAA");
        assertThat(first.title()).isEqualTo("تفسير رؤيا الماء");
        assertThat(first.publishedAt()).isEqualTo(Instant.parse("2026-09-20T18:00:00Z"));
        assertThat(first.url()).isEqualTo("https://www.youtube.com/watch?v=AAAAAAAAAAA");
        assertThat(first.thumbnailUrl()).isEqualTo("https://i1.ytimg.com/vi/AAAAAAAAAAA/hqdefault.jpg");

        FeedEntry second = entries.get(1);
        assertThat(second.publishedAt()).isEqualTo(Instant.parse("2026-09-10T05:30:00Z"));
        assertThat(second.url()).isEqualTo("https://www.youtube.com/watch?v=BBBBBBBBBBB");
        assertThat(second.thumbnailUrl()).isNull();
    }

    @Test
    void rejectsDoctype() {
        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE feed [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>"
                + "<feed xmlns=\"http://www.w3.org/2005/Atom\"><title>&x;</title></feed>";
        assertThatThrownBy(() -> YoutubeFeedParser.parse(xxe.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emptyInputGivesNoEntries() {
        assertThat(YoutubeFeedParser.parse(new byte[0])).isEmpty();
    }
}
