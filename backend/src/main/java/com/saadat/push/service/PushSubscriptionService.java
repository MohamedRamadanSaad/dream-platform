package com.saadat.push.service;

import com.saadat.notifications.domain.PushSubscription;
import com.saadat.notifications.repo.PushSubscriptionRepository;
import com.saadat.push.api.PushSubscriptionRequest;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Stores browser push subscriptions; an endpoint belongs to the last user who registered it. */
@Service
public class PushSubscriptionService {

    private static final int USER_AGENT_MAX = 512;

    private final PushSubscriptionRepository repository;
    private final Clock clock;

    public PushSubscriptionService(PushSubscriptionRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional
    public void subscribe(UUID userId, PushSubscriptionRequest request) {
        String endpoint = request.endpoint().trim();
        PushSubscription subscription = repository.findByEndpoint(endpoint).orElseGet(() -> {
            PushSubscription s = new PushSubscription();
            s.setEndpoint(endpoint);
            s.setCreatedAt(clock.instant());
            return s;
        });
        subscription.setUserId(userId);
        subscription.setP256dh(request.keys().p256dh().trim());
        subscription.setAuth(request.keys().auth().trim());
        String ua = request.userAgent();
        subscription.setUserAgent(ua == null ? null : ua.length() > USER_AGENT_MAX ? ua.substring(0, USER_AGENT_MAX) : ua);
        subscription.setFailures(0);
        repository.save(subscription);
    }

    @Transactional
    public void unsubscribe(UUID userId, String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            return;
        }
        repository.deleteByEndpointAndUserId(endpoint.trim(), userId);
    }

    @Transactional
    public void removeAll(UUID userId) {
        repository.deleteByUserId(userId);
    }
}
