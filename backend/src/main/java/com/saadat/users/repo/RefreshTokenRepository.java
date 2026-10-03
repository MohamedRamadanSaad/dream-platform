package com.saadat.users.repo;

import com.saadat.users.domain.RefreshToken;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /** True when the family has a token issued after {@code createdAt} (i.e. that token was rotated). */
    boolean existsByFamilyIdAndCreatedAtAfter(UUID familyId, Instant createdAt);

    /** Did the user ever sign in (any family)? */
    boolean existsByUserId(UUID userId);

    /** Does the family belong to the user (any of its tokens, active or not)? */
    boolean existsByUserIdAndFamilyId(UUID userId, UUID familyId);

    /** The user's active (non-revoked, non-expired) tokens — normally one per signed-in device. */
    @Query("select t from RefreshToken t where t.userId = :userId and t.revokedAt is null and t.expiresAt > :now")
    List<RefreshToken> findActiveByUserId(@Param("userId") UUID userId, @Param("now") Instant now);

    /** [familyId, first createdAt, last createdAt] of each family: when the device signed in and was last active. */
    @Query("select t.familyId, min(t.createdAt), max(t.createdAt) from RefreshToken t "
            + "where t.familyId in :familyIds group by t.familyId")
    List<Object[]> findFamilySpans(@Param("familyIds") Collection<UUID> familyIds);

    /** User-Agents of the user's tokens issued since {@code since} (sign-ins and refreshes, any state). */
    @Query("select distinct t.userAgent from RefreshToken t where t.userId = :userId and t.createdAt >= :since")
    List<String> findUserAgentsSince(@Param("userId") UUID userId, @Param("since") Instant since);

    /** Reuse detection / logout / signing a device out: revokes every still-active token of the family. */
    @Modifying
    @Transactional
    @Query("update RefreshToken t set t.revokedAt = :now where t.familyId = :familyId and t.revokedAt is null")
    int revokeFamily(@Param("familyId") UUID familyId, @Param("now") Instant now);

    /** Logout everywhere / account deletion. */
    @Modifying
    @Transactional
    @Query("update RefreshToken t set t.revokedAt = :now where t.userId = :userId and t.revokedAt is null")
    int revokeAllForUser(@Param("userId") UUID userId, @Param("now") Instant now);

    /** "Sign out the other devices": every family of the user except {@code keepFamilyId}. */
    @Modifying
    @Transactional
    @Query("update RefreshToken t set t.revokedAt = :now where t.userId = :userId and t.familyId <> :keepFamilyId "
            + "and t.revokedAt is null")
    int revokeAllForUserExceptFamily(@Param("userId") UUID userId, @Param("keepFamilyId") UUID keepFamilyId,
                                     @Param("now") Instant now);

    /** Housekeeping. Soft: flags expired tokens {@code deleted = true}; flagged rows are never matched again. */
    @Modifying
    @Transactional
    @Query("delete from RefreshToken t where t.expiresAt < :before")
    int deleteExpiredBefore(@Param("before") Instant before);
}
