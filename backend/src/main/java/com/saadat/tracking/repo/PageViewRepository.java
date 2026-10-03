package com.saadat.tracking.repo;

import com.saadat.tracking.domain.PageView;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Writes of {@code page_views}; the analytics aggregates live in {@code admin.analytics.AnalyticsQueries}. */
public interface PageViewRepository extends JpaRepository<PageView, UUID> {

    List<PageView> findBySessionIdOrderByCreatedAtAsc(String sessionId);

    /**
     * When the user was last on the site before the current visit: the latest page view of theirs that belongs to
     * another visit ({@code visit} = the current visit id, '' when unknown) and is older than {@code before}.
     */
    @Query("select max(v.createdAt) from PageView v where v.userId = :userId and v.sessionId <> :visit "
            + "and v.createdAt < :before")
    Instant lastSeenBefore(@Param("userId") UUID userId, @Param("visit") String visit,
                           @Param("before") Instant before);
}
