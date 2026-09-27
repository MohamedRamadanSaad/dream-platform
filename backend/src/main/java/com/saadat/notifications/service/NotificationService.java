package com.saadat.notifications.service;

import com.saadat.common.domain.NotificationType;
import com.saadat.mail.MessageText;
import com.saadat.notifications.domain.Notification;
import com.saadat.notifications.repo.NotificationRepository;
import com.saadat.users.domain.User;
import com.saadat.users.repo.UserRepository;
import java.time.Clock;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * In-app notifications + push + (per {@link NotificationEmailPolicy}) e-mail.
 *
 * <p>Title/body come from messages {@code notif.<TYPE>.title} / {@code notif.<TYPE>.body} in the recipient's
 * locale; {@code {key}} placeholders are filled from {@code args} (Instants are formatted for the recipient).
 * The row joins the caller's transaction; push and e-mail are dispatched asynchronously <b>after commit</b>.
 *
 * <p>Well-known {@code args} keys used by the messages/templates: {@code userName} (the dreamer, for
 * interpreter-facing types), {@code credits}, {@code packageName}, {@code amount}, {@code currency},
 * {@code orderRef}, {@code expectedBy} (Instant). {@code ref} (optional) is stored as email_log.ref.
 */
@Slf4j
@Service
public class NotificationService {

    public static final String TITLE_KEY = "notif.%s.title";
    public static final String BODY_KEY = "notif.%s.body";
    /** Optional arg: correlation key stored in email_log.ref (defaults to the notification id). */
    public static final String ARG_REF = "ref";

    private static final int TITLE_MAX = 300;
    private static final int LINK_MAX = 500;

    private final NotificationRepository repository;
    private final UserRepository userRepository;
    private final MessageText messageText;
    private final NotificationDispatcher dispatcher;
    private final InterpreterDirectory interpreterDirectory;
    private final Clock clock;

    public NotificationService(NotificationRepository repository, UserRepository userRepository,
                               MessageText messageText, NotificationDispatcher dispatcher,
                               InterpreterDirectory interpreterDirectory, Clock clock) {
        this.repository = repository;
        this.userRepository = userRepository;
        this.messageText = messageText;
        this.dispatcher = dispatcher;
        this.interpreterDirectory = interpreterDirectory;
        this.clock = clock;
    }

    /**
     * Creates the notification for {@code userId} and schedules push + e-mail after commit. Unknown or deleted
     * users are ignored (logged). {@code link} is an SPA path such as {@code /me/dreams/{id}} (nullable).
     */
    @Transactional
    public void notify(UUID userId, NotificationType type, Map<String, Object> args, String link) {
        User user = userId == null ? null : userRepository.findById(userId).orElse(null);
        if (user == null || user.isDeleted()) {
            log.info("Skipping {} notification for unknown/deleted user {}", type, userId);
            return;
        }
        Map<String, Object> safeArgs = args == null ? Collections.emptyMap() : args;
        String title = truncate(messageText.get(String.format(TITLE_KEY, type.name()), user.getLocale(), safeArgs),
                TITLE_MAX);
        String body = messageText.get(String.format(BODY_KEY, type.name()), user.getLocale(), safeArgs);

        Notification n = new Notification();
        n.setUserId(userId);
        n.setType(type);
        n.setTitle(title);
        n.setBody(body);
        n.setLink(truncate(link, LINK_MAX));
        n.setCreatedAt(clock.instant());
        repository.save(n);

        NotificationEvent event = new NotificationEvent(n.getId(), userId, type, title, body, n.getLink(),
                new HashMap<>(safeArgs));
        afterCommit(() -> dispatcher.dispatch(event));
    }

    /** Notifies every interpreter account (see {@link InterpreterDirectory}). */
    @Transactional
    public void notifyInterpreters(NotificationType type, Map<String, Object> args, String link) {
        List<UUID> ids = interpreterDirectory.interpreterUserIds();
        if (ids.isEmpty()) {
            log.warn("No interpreter account yet — {} notification not delivered", type);
        }
        for (UUID id : ids) {
            notify(id, type, args, link);
        }
    }

    @Transactional(readOnly = true)
    public Page<Notification> list(UUID userId, Pageable pageable) {
        return repository.findByUserIdOrderByCreatedAtDesc(userId, pageable);
    }

    @Transactional(readOnly = true)
    public long unreadCount(UUID userId) {
        return repository.countByUserIdAndReadAtIsNull(userId);
    }

    @Transactional
    public void markRead(UUID userId, UUID notificationId) {
        repository.markRead(notificationId, userId, clock.instant());
    }

    @Transactional
    public void markAllRead(UUID userId) {
        repository.markAllRead(userId, clock.instant());
    }

    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() > max ? value.substring(0, max) : value;
    }
}
