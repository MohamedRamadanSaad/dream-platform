package com.saadat.youtube.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Row of {@code youtube_seen}: when the user last opened the YouTube button. PK = user id (assigned). */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "youtube_seen")
public class YoutubeSeen {

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "seen_at", nullable = false)
    private Instant seenAt;

    public YoutubeSeen(UUID userId, Instant seenAt) {
        this.userId = userId;
        this.seenAt = seenAt;
    }
}
