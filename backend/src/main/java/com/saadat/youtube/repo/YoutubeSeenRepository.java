package com.saadat.youtube.repo;

import com.saadat.youtube.domain.YoutubeSeen;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface YoutubeSeenRepository extends JpaRepository<YoutubeSeen, UUID> {
}
