package com.saadat.tracking.repo;

import com.saadat.tracking.domain.PageView;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Writes of {@code page_views}; the analytics aggregates live in {@code admin.analytics.AnalyticsQueries}. */
public interface PageViewRepository extends JpaRepository<PageView, UUID> {

    List<PageView> findBySessionIdOrderByCreatedAtAsc(String sessionId);
}
