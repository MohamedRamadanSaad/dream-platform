package com.saadat.notifications.repo;

import com.saadat.notifications.domain.PushSubscription;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

public interface PushSubscriptionRepository extends JpaRepository<PushSubscription, UUID> {

    List<PushSubscription> findByUserId(UUID userId);

    Optional<PushSubscription> findByEndpoint(String endpoint);

    /** Used on unsubscribe and when the push service answers 404/410. */
    @Transactional
    long deleteByEndpoint(String endpoint);

    @Transactional
    long deleteByEndpointAndUserId(String endpoint, UUID userId);

    @Transactional
    long deleteByUserId(UUID userId);
}
