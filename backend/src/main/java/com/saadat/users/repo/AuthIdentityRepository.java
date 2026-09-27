package com.saadat.users.repo;

import com.saadat.common.domain.AuthProvider;
import com.saadat.users.domain.AuthIdentity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuthIdentityRepository extends JpaRepository<AuthIdentity, UUID> {

    Optional<AuthIdentity> findByProviderAndProviderSubject(AuthProvider provider, String providerSubject);

    List<AuthIdentity> findByUserId(UUID userId);

    boolean existsByUserIdAndProvider(UUID userId, AuthProvider provider);
}
