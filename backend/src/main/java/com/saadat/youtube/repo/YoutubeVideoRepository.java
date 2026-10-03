package com.saadat.youtube.repo;

import com.saadat.youtube.domain.YoutubeVideo;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface YoutubeVideoRepository extends JpaRepository<YoutubeVideo, String> {

    long countByPublishedAtAfter(Instant since);

    List<YoutubeVideo> findTop5ByOrderByPublishedAtDesc();

    List<YoutubeVideo> findTop5ByPublishedAtAfterOrderByPublishedAtDesc(Instant since);

    List<YoutubeVideo> findByPublishedAtAfterOrderByPublishedAtDesc(Instant since, org.springframework.data.domain.Pageable page);
}
