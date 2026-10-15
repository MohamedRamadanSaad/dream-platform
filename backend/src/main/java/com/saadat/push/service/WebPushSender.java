package com.saadat.push.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.saadat.config.props.AppProperties;
import com.saadat.notifications.domain.PushSubscription;
import com.saadat.notifications.repo.PushSubscriptionRepository;
import java.security.Security;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import nl.martijndwars.webpush.Encoding;
import nl.martijndwars.webpush.PushService;
import nl.martijndwars.webpush.Urgency;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Web Push (VAPID, {@code app.push.*}) via {@code nl.martijndwars:web-push}. Payload JSON:
 * {@code {title, body, url}}. When the keys are blank, sending is skipped (logged at DEBUG per send, and one WARN at
 * start-up when only part of the configuration is set).
 *
 * <p>Every message is sent with {@code TTL} 24 h (kept by the push service while the phone is offline) and
 * {@code Urgency: high} (delivered at once, even in battery saver), encrypted as RFC 8291 {@code aes128gcm}: Apple's
 * push service (iPhone / Safari) accepts only that encoding, the library default ({@code aesgcm}) is a legacy draft.
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
    /** How long the push service keeps an undelivered message (phone off / offline). */
    static final int TTL_SECONDS = (int) Duration.ofHours(24).toSeconds();

    private final AppProperties.Push config;
    private final PushSubscriptionRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    private volatile PushService pushService;
    private volatile String pushServiceKeys;

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

    /** One WARN at start-up when the public key is set (the app offers push) but the server cannot send. */
    @EventListener(ApplicationReadyEvent.class)
    public void warnWhenHalfConfigured() {
        if (notBlank(config.getPublicKey()) && !isConfigured()) {
            log.warn("Web push is half configured: VAPID_PUBLIC_KEY is set but {} missing, so no notification will be"
                    + " sent. Set VAPID_PRIVATE_KEY and VAPID_SUBJECT (mailto:...) or run deploy/ops/vapid-keys.sh.",
                    !notBlank(config.getPrivateKey()) && !notBlank(config.getSubject())
                            ? "VAPID_PRIVATE_KEY and VAPID_SUBJECT are"
                            : !notBlank(config.getPrivateKey()) ? "VAPID_PRIVATE_KEY is" : "VAPID_SUBJECT is");
        }
    }

    /** Sends to every subscription of the user; never throws. Returns how many push services accepted it. */
    public int sendToUser(UUID userId, String title, String body, String url) {
        if (!isConfigured()) {
            log.debug("Web push not configured, skipping push to user {}", userId);
            return 0;
        }
        List<PushSubscription> subscriptions = repository.findByUserId(userId);
        if (subscriptions.isEmpty()) {
            return 0;
        }
        String payload = payload(title, body, url);
        int sent = 0;
        for (PushSubscription subscription : subscriptions) {
            if (send(subscription, payload)) {
                sent++;
            }
        }
        return sent;
    }

    /** The message as sent: TTL 24 h, urgency high (see the class comment). */
    static nl.martijndwars.webpush.Notification notification(PushSubscription subscription, String payload)
            throws java.security.GeneralSecurityException {
        return nl.martijndwars.webpush.Notification.builder()
                .endpoint(subscription.getEndpoint())
                .userPublicKey(subscription.getP256dh())
                .userAuth(subscription.getAuth())
                .payload(payload)
                .ttl(TTL_SECONDS)
                .urgency(Urgency.HIGH)
                .build();
    }

    boolean send(PushSubscription subscription, String payload) {
        try {
            var response = service().send(notification(subscription, payload), Encoding.AES128GCM);
            int status = response.getStatusLine().getStatusCode();
            if (status >= HTTP_OK_MIN && status <= HTTP_OK_MAX) {
                subscription.setLastSuccessAt(clock.instant());
                subscription.setFailures(0);
                repository.save(subscription);
                return true;
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
        return false;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
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

    /** Built once per key set (a key change, e.g. by vapid-keys.sh plus a restart or a test, builds a new one). */
    private PushService service() throws java.security.GeneralSecurityException {
        String keys = config.getPublicKey().trim() + '|' + config.getPrivateKey().trim() + '|' + config.getSubject().trim();
        PushService current = pushService;
        if (current == null || !keys.equals(pushServiceKeys)) {
            synchronized (this) {
                current = pushService;
                if (current == null || !keys.equals(pushServiceKeys)) {
                    current = new PushService(config.getPublicKey().trim(), config.getPrivateKey().trim(),
                            config.getSubject().trim());
                    pushService = current;
                    pushServiceKeys = keys;
                }
            }
        }
        return current;
    }
}
