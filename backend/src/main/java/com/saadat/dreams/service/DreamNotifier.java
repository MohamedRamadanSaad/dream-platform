package com.saadat.dreams.service;

import com.saadat.common.domain.Locale;
import com.saadat.common.domain.NotificationType;
import com.saadat.common.tx.AfterCommit;
import com.saadat.dreams.domain.Dream;
import com.saadat.dreams.repo.DreamRepository;
import com.saadat.notifications.service.InterpreterDirectory;
import com.saadat.notifications.service.NotificationService;
import com.saadat.settings.SettingKeys;
import com.saadat.settings.SettingsService;
import com.saadat.users.domain.User;
import com.saadat.users.repo.UserRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Dream lifecycle notifications (contract rule 10). Every method schedules the work after the current
 * transaction commits; NotificationService sends the e-mail/push per type. Interpreter events go to every
 * interpreter from {@link InterpreterDirectory}.
 *
 * <p>Notification args: {@code dreamId, excerpt, expectedBy, userName} (+ {@code waitMessage} for
 * DREAM_RECEIVED).
 */
@Component
@RequiredArgsConstructor
public class DreamNotifier {

    private final NotificationService notificationService;
    private final InterpreterDirectory interpreterDirectory;
    private final DreamRepository dreamRepository;
    private final UserRepository userRepository;
    private final SettingsService settingsService;
    private final AfterCommit afterCommit;

    /** DREAM_RECEIVED to the owner + DREAM_SUBMITTED to interpreters, per dream. */
    public void submitted(List<UUID> dreamIds) {
        List<UUID> ids = List.copyOf(dreamIds);
        afterCommit.run("dreams-submitted", () -> {
            for (UUID id : ids) {
                Dream d = dreamRepository.findById(id).orElse(null);
                if (d == null) {
                    continue;
                }
                User owner = userRepository.findById(d.getUserId()).orElse(null);
                Map<String, Object> args = args(d, owner);
                args.put("waitMessage", waitMessage(owner));
                notificationService.notify(d.getUserId(), NotificationType.DREAM_RECEIVED, args, DreamLinks.user(id));
                toInterpreters(NotificationType.DREAM_SUBMITTED, args(d, owner), DreamLinks.admin(id));
            }
        });
    }

    public void interpreterQuestion(UUID dreamId) {
        afterCommit.run("interpreter-question:" + dreamId, () -> toOwner(dreamId, NotificationType.INTERPRETER_QUESTION));
    }

    public void interpretationReady(UUID dreamId) {
        afterCommit.run("interpretation-ready:" + dreamId, () -> toOwner(dreamId, NotificationType.INTERPRETATION_READY));
    }

    public void userReplied(UUID dreamId) {
        afterCommit.run("user-replied:" + dreamId, () -> {
            Dream d = dreamRepository.findById(dreamId).orElse(null);
            if (d != null) {
                User owner = userRepository.findById(d.getUserId()).orElse(null);
                toInterpreters(NotificationType.USER_REPLIED, args(d, owner), DreamLinks.admin(dreamId));
            }
        });
    }

    private void toOwner(UUID dreamId, NotificationType type) {
        Dream d = dreamRepository.findById(dreamId).orElse(null);
        if (d == null) {
            return;
        }
        User owner = userRepository.findById(d.getUserId()).orElse(null);
        notificationService.notify(d.getUserId(), type, args(d, owner), DreamLinks.user(dreamId));
    }

    private void toInterpreters(NotificationType type, Map<String, Object> args, String link) {
        for (UUID interpreterId : interpreterDirectory.interpreterUserIds()) {
            notificationService.notify(interpreterId, type, new LinkedHashMap<>(args), link);
        }
    }

    private static Map<String, Object> args(Dream d, User owner) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("dreamId", d.getId().toString());
        args.put("excerpt", DreamMapper.excerpt(d.getText()));
        args.put("expectedBy", d.getExpectedBy());
        args.put("userName", owner == null ? "" : owner.getName());
        return args;
    }

    private String waitMessage(User owner) {
        boolean en = owner != null && owner.getLocale() == Locale.EN;
        return settingsService.getString(en ? SettingKeys.WAIT_MESSAGE_EN : SettingKeys.WAIT_MESSAGE_AR, "");
    }
}
