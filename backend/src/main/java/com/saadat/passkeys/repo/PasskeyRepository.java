package com.saadat.passkeys.repo;

import com.saadat.passkeys.domain.Passkey;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface PasskeyRepository extends JpaRepository<Passkey, UUID> {

    /** The user's passkeys, newest first. */
    List<Passkey> findByUserIdOrderByCreatedAtDesc(UUID userId);

    /** One passkey when it belongs to the user. */
    Optional<Passkey> findByIdAndUserId(UUID id, UUID userId);

    Optional<Passkey> findByCredentialId(byte[] credentialId);

    boolean existsByCredentialId(byte[] credentialId);

    long countByUserId(UUID userId);

    /** Account deletion. Soft: {@code @SoftDelete} on {@link Passkey} turns this JPQL delete into an update. */
    @Modifying
    @Transactional
    @Query("delete from Passkey p where p.userId = :userId")
    int deleteAllOfUser(@Param("userId") UUID userId);
}
