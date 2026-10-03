package com.saadat.dreams.repo;

import com.saadat.common.domain.DreamStatus;
import com.saadat.dreams.domain.Dream;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DreamRepository extends JpaRepository<Dream, UUID> {

    // ---- user views ----
    Optional<Dream> findByIdAndUserId(UUID id, UUID userId);

    Page<Dream> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    List<Dream> findByUserIdOrderByCreatedAtDesc(UUID userId);

    Page<Dream> findByUserIdAndStatusOrderByCreatedAtDesc(UUID userId, DreamStatus status, Pageable pageable);

    List<Dream> findByUserIdAndStatusOrderByCreatedAtDesc(UUID userId, DreamStatus status);

    long countByUserIdAndStatus(UUID userId, DreamStatus status);

    long countByUserIdAndStatusNot(UUID userId, DreamStatus status);

    /** Locks the caller's dreams for submit (all must be DRAFT — checked by the service). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Dream d where d.id in :ids and d.userId = :userId")
    List<Dream> findAllByIdInAndUserIdForUpdate(@Param("ids") Collection<UUID> ids, @Param("userId") UUID userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Dream d where d.id = :id")
    Optional<Dream> findByIdForUpdate(@Param("id") UUID id);

    // ---- admin queue ----
    Page<Dream> findByStatusOrderBySubmittedAtAsc(DreamStatus status, Pageable pageable);

    List<Dream> findByStatusOrderBySubmittedAtAsc(DreamStatus status);

    /** Admin "ALL": everything except drafts. */
    Page<Dream> findByStatusNotOrderBySubmittedAtAsc(DreamStatus status, Pageable pageable);

    Page<Dream> findByStatusInOrderBySubmittedAtAsc(Collection<DreamStatus> statuses, Pageable pageable);

    long countByStatus(DreamStatus status);

    /** Overdue = IN_REVIEW and expected_by in the past. */
    long countByStatusAndExpectedByBefore(DreamStatus status, Instant now);

    long countByStatusAndInterpretedAtAfter(DreamStatus status, Instant since);

    // ---- schedulers ----
    /** Reply reminder candidates: AWAITING_USER_REPLY paused before {@code before}. */
    List<Dream> findByStatusAndSlaPausedAtBefore(DreamStatus status, Instant before);

    /** Testimonial request candidates: INTERPRETED before {@code before}. */
    List<Dream> findByStatusAndInterpretedAtBefore(DreamStatus status, Instant before);

    // ---- analytics ----
    /** [userId, count] of non-draft dreams. */
    @Query("select d.userId, count(d) from Dream d where d.status <> :excluded group by d.userId")
    List<Object[]> countByUserExcludingStatus(@Param("excluded") DreamStatus excluded);

    /** Average hours between submit and interpretation for dreams interpreted since {@code since}. */
    @Query(value = "select cast(coalesce(avg(extract(epoch from (interpreted_at - submitted_at)) / 3600.0), 0) as double precision) "
            + "from dreams where not deleted and status = 'INTERPRETED' and interpreted_at >= :since",
            nativeQuery = true)
    double averageResponseHoursSince(@Param("since") Instant since);

    /** [countryCode, dreams] — dreams submitted since {@code since}, by the owner's country. */
    @Query(value = "select u.country_code, count(*) from dreams d join users u on u.id = d.user_id "
            + "where not d.deleted and d.submitted_at >= :since group by u.country_code", nativeQuery = true)
    List<Object[]> countSubmittedByCountrySince(@Param("since") Instant since);
}
