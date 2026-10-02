package com.saadat.passkeys.repo;

import com.saadat.passkeys.domain.PasskeyChallenge;
import com.saadat.passkeys.domain.PasskeyPurpose;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface PasskeyChallengeRepository extends JpaRepository<PasskeyChallenge, UUID> {

    /**
     * Marks the challenge used when it exists, has this purpose, is unused and has not expired — atomically, so two
     * concurrent requests can never both consume it. Returns 1 when this call consumed it, else 0. Needs the caller's
     * transaction.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update PasskeyChallenge c set c.usedAt = :now where c.requestId = :requestId and c.purpose = :purpose "
            + "and c.usedAt is null and c.expiresAt > :now")
    int consume(@Param("requestId") UUID requestId, @Param("purpose") PasskeyPurpose purpose,
                @Param("now") Instant now);

    /** Like {@link #consume} for a challenge that belongs to {@code userId} (registration). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update PasskeyChallenge c set c.usedAt = :now where c.requestId = :requestId and c.purpose = :purpose "
            + "and c.userId = :userId and c.usedAt is null and c.expiresAt > :now")
    int consumeOwn(@Param("requestId") UUID requestId, @Param("purpose") PasskeyPurpose purpose,
                   @Param("userId") UUID userId, @Param("now") Instant now);

    @Modifying
    @Transactional
    @Query("delete from PasskeyChallenge c where c.expiresAt < :before")
    int deleteExpiredBefore(@Param("before") Instant before);
}
