package com.saadat.users.repo;

import com.saadat.common.domain.CodePurpose;
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

    /** Latest unused link for an e-mail, any purpose. */
    Optional<MagicLink> findTopByEmailAndUsedAtIsNullOrderByCreatedAtDesc(String email);

    /**
     * Latest unused link of an e-mail for one purpose (the {email, code} verification paths): a sign-up code never
     * signs anyone in through /auth/magic/verify, and a sign-in code never opens a sign-up or a password reset.
     */
    Optional<MagicLink> findTopByEmailAndPurposeAndUsedAtIsNullOrderByCreatedAtDesc(String email, CodePurpose purpose);

    /** The link that gave this ticket (SHA-256 of the raw ticket). */
    Optional<MagicLink> findByTicketHash(String ticketHash);

    /** Throttling helper: how many links/codes (all purposes) were requested for this e-mail since {@code since}. */
    long countByEmailAndCreatedAtAfter(String email, Instant since);

    /**
     * Housekeeping. Soft: flags expired links {@code deleted = true}; flagged rows are never matched again. A row whose
     * ticket (V37) is still valid is kept until the ticket expires too.
     */
    @Modifying
    @Transactional
    @Query("delete from MagicLink m where m.expiresAt < :before "
            + "and (m.ticketExpiresAt is null or m.ticketExpiresAt < :before)")
    int deleteExpiredBefore(@Param("before") Instant before);
}
