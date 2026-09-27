package com.saadat.users.repo;

import com.saadat.users.domain.UserSession;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserSessionRepository extends JpaRepository<UserSession, UUID> {

    long countByUserId(UUID userId);

    Optional<UserSession> findTopByUserIdOrderByStartedAtDesc(UUID userId);

    long countByStartedAtAfter(Instant since);

    /** [countryCode, visits] rows since {@code since} (use Instant.EPOCH for "all"). */
    @Query("select s.countryCode, count(s) from UserSession s where s.startedAt >= :since group by s.countryCode")
    List<Object[]> countVisitsByCountrySince(@Param("since") Instant since);

    /** [userId, visits] rows since {@code since}. */
    @Query("select s.userId, count(s) from UserSession s where s.userId is not null and s.startedAt >= :since "
            + "group by s.userId")
    List<Object[]> countVisitsByUserSince(@Param("since") Instant since);
}
