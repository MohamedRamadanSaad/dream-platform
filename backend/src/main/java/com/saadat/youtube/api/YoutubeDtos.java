package com.saadat.youtube.api;

import com.saadat.youtube.domain.YoutubeVideo;
import java.time.Instant;
import java.util.List;

/** YouTube-as-a-notification DTOs (spec §8). */
public final class YoutubeDtos {

    private YoutubeDtos() {
    }

    public record YoutubeVideoDto(String id, String title, String url, Instant publishedAt, String thumbnailUrl) {
        public static YoutubeVideoDto from(YoutubeVideo v) {
            return new YoutubeVideoDto(v.getId(), v.getTitle(), v.getUrl(), v.getPublishedAt(), v.getThumbnailUrl());
        }
    }

    /** {@code GET /youtube/unseen}: {@code count} = new videos since the last press, {@code latest} = those videos. */
    public record YoutubeUnseen(long count, List<YoutubeVideoDto> latest) {
    }

    /** {@code POST /admin/youtube/refresh} result. */
    public record YoutubeRefreshResult(int fetched) {
    }
}
