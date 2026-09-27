package com.saadat.push.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.saadat.config.props.AppProperties;
import com.saadat.notifications.domain.PushSubscription;
import com.saadat.notifications.repo.PushSubscriptionRepository;
import java.security.Security;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import nl.martijndwars.webpush.PushService;
import nl.martijndwars.webpush.Subscription;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.springframework.stereotype.Component;

/**
 * Web Push (VAPID, {@code app.push.*}) via {@code nl.martijndwars:web-push}. Payload JSON:
 * {@code {title, body, url}}. When the keys are blank, sending is skipped (logged at DEBUG).
 * A 404/410 from the push service deletes the subscription; other failures increment {@code failures}.
 */
@Slf4j
@Component
public class WebPushSender {

    static {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    private static final int HTTP_NOT_FOUND = 404;
    private static final int HTTP_GONE = 410;
    private static final int HTTP_OK_MIN = 200;
    private static final int HTTP_OK_MAX = 299;

    private final AppProperties.Push config;
    private final PushSubscriptionRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    private volatile PushService pushService;

    public WebPushSender(AppProperties properties, PushSubscriptionRepository repository, ObjectMapper objectMapper,
                         Clock clock) {
        this.config = properties.getPush();
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public boolean isConfigured() {
        return config.isConfigured();
    }

    /** Sends to every subscription of the user; never throws. */
    public void sendToUser(UUID userId, String title, String body, String url) {
        if (!isConfigured()) {
            log.debug("Web push not configured — skipping push to user {}", userId);
            return;
        }
        List<PushSubscription> subscriptions = repository.findByUserId(userId);
        if (subscriptions.isEmpty()) {
            return;
        }
        String payload = payload(title, body, url);
        for (PushSubscription subscription : subscriptions) {
            send(subscription, payload);
        }
    }

    void send(PushSubscription subscription, String payload) {
        try {
            nl.martijndwars.webpush.Notification notification = new nl.martijndwars.webpush.Notification(
                    new Subscription(subscription.getEndpoint(),
                            new Subscription.Keys(subscription.getP256dh(), subscription.getAuth())),
                    payload);
            var response = service().send(notification);
            int status = response.getStatusLine().getStatusCode();
            if (status >= HTTP_OK_MIN && status <= HTTP_OK_MAX) {
                subscription.setLastSuccessAt(clock.instant());
                subscription.setFailures(0);
                repository.save(subscription);
            } else if (status == HTTP_NOT_FOUND || status == HTTP_GONE) {
                log.info("Push subscription {} is gone ({}), deleting", subscription.getId(), status);
                repository.deleteByEndpoint(subscription.getEndpoint());
            } else {
                log.warn("Push to subscription {} failed with HTTP {}", subscription.getId(), status);
                markFailure(subscription);
            }
        } catch (Exception e) {
            log.warn("Push to subscription {} failed: {}", subscription.getId(), e.toString());
            markFailure(subscription);
        }
    }

    private void markFailure(PushSubscription subscription) {
        try {
            subscription.setFailures(subscription.getFailures() + 1);
            repository.save(subscription);
        } catch (RuntimeException e) {
            log.debug("Could not record push failure for {}", subscription.getId(), e);
        }
    }

    private String payload(String title, String body, String url) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("title", title);
        json.put("body", body);
        json.put("url", url);
        try {
            return objectMapper.writeValueAsString(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize push payload", e);
        }
    }

    private PushService service() throws java.security.GeneralSecurityException {
        PushService current = pushService;
        if (current == null) {
            synchronized (this) {
                current = pushService;
                if (current == null) {
                    current = new PushService(config.getPublicKey().trim(), config.getPrivateKey().trim(),
                            config.getSubject().trim());
                    pushService = current;
                }
            }
        }
        return current;
    }
}
