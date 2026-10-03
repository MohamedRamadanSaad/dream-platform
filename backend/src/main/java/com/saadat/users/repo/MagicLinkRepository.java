package com.saadat.users.repo;

import com.saadat.users.domain.MagicLink;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface MagicLinkRepository extends JpaRepository<MagicLink, UUID> {

    Optional<MagicLink> findByTokenHash(String tokenHash);

    /** Latest unused link for an e-mail (used by the {email, code} verification path). */
    Optional<MagicLink> findTopByEmailAndUsedAtIsNullOrderByCreatedAtDesc(String email);

    /** Throttling helper: how many links were requested for this e-mail since {@code since}. */
    long countByEmailAndCreatedAtAfter(String email, Instant since);

    /** Housekeeping. Soft: flags expired links {@code deleted = true}; flagged rows are never matched again. */
    @Modifying
    @Transactional
    @Query("delete from MagicLink m where m.expiresAt < :before")
    int deleteExpiredBefore(@Param("before") Instant before);
}
