package com.saadat.youtube.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Row of {@code youtube_videos}; PK is the YouTube video id (assigned, upserted by the poller). */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "youtube_videos")
public class YoutubeVideo {

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 32)
    private String id;

    @Column(name = "title", nullable = false, length = 500)
    private String title;

    @Column(name = "published_at", nullable = false)
    private Instant publishedAt;

    @Column(name = "thumbnail_url", length = 500)
    private String thumbnailUrl;

    @Column(name = "url", nullable = false, length = 500)
    private String url;

    @Column(name = "fetched_at", nullable = false)
    private Instant fetchedAt;
}
