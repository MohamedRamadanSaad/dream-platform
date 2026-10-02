package com.saadat.users.repo;

import com.saadat.common.domain.Role;
import com.saadat.users.domain.User;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);

    List<User> findByRole(Role role);

    /** Active users of {@code role} who opted in to news e-mails (marketing_opt_in). */
    List<User> findByRoleAndMarketingOptInTrueAndDeletedAtIsNull(Role role);

    /**
     * {@code SELECT ... FROM users WHERE id = ? FOR UPDATE} — serializes credit consumption per user
     * (contract rule 2). Must be called inside a transaction.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") UUID id);

    /**
     * Role of an active (non-deleted) account whose refresh-token family {@code familyId} still has an active
     * (non-revoked, non-expired) token — one query per request with an access token carrying {@code sid}.
     */
    @Query("select u.role from User u where u.id = :userId and u.deletedAt is null and exists ("
            + "select t.id from RefreshToken t where t.familyId = :familyId and t.userId = u.id "
            + "and t.revokedAt is null and t.expiresAt > :now)")
    Optional<Role> findActiveRoleInSession(@Param("userId") UUID userId, @Param("familyId") UUID familyId,
                                           @Param("now") Instant now);

    Page<User> findByDeletedAtIsNull(Pageable pageable);

    @Query("select u from User u where u.deletedAt is null and ("
            + "lower(u.name) like lower(concat('%', :q, '%')) or lower(u.email) like lower(concat('%', :q, '%')))")
    Page<User> search(@Param("q") String q, Pageable pageable);

    long countByCreatedAtAfter(Instant since);

    long countByCountryCode(String countryCode);

    /** [countryCode, userCount] rows. */
    @Query("select u.countryCode, count(u) from User u where u.deletedAt is null group by u.countryCode")
    List<Object[]> countUsersByCountry();
}
