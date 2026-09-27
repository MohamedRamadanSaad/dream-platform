package com.saadat.notifications.service;

import com.saadat.common.domain.NotificationType;
import com.saadat.mail.MailService;
import com.saadat.publicapi.WaitTimeView;
import com.saadat.push.service.WebPushSender;
import com.saadat.users.domain.User;
import com.saadat.users.repo.UserRepository;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/** Delivers a committed notification over push and (per policy) e-mail on the async pool. Never throws. */
@Slf4j
@Component
public class NotificationDispatcher {

    static final String MODEL_NAME = "name";
    static final String MODEL_WAIT_MESSAGE = "waitMessage";

    private final UserRepository userRepository;
    private final WebPushSender webPushSender;
    private final MailService mailService;
    private final NotificationEmailPolicy emailPolicy;
    private final WaitTimeView waitTimeView;

    public NotificationDispatcher(UserRepository userRepository, WebPushSender webPushSender, MailService mailService,
                                  NotificationEmailPolicy emailPolicy, WaitTimeView waitTimeView) {
        this.userRepository = userRepository;
        this.webPushSender = webPushSender;
        this.mailService = mailService;
        this.emailPolicy = emailPolicy;
        this.waitTimeView = waitTimeView;
    }

    @Async
    public void dispatch(NotificationEvent event) {
        User user = userRepository.findById(event.userId()).orElse(null);
        if (user == null || user.isDeleted()) {
            return;
        }
        try {
            webPushSender.sendToUser(user.getId(), event.title(), event.body(), event.link());
        } catch (RuntimeException e) {
            log.warn("Push for notification {} failed", event.notificationId(), e);
        }
        Optional<String> template = emailPolicy.templateFor(event.type());
        if (template.isEmpty()) {
            return;
        }
        try {
            Map<String, Object> model = new HashMap<>(event.args());
            model.putIfAbsent(MODEL_NAME, user.getName());
            if (event.link() != null) {
                model.put(MailService.MODEL_LINK, event.link());
            }
            if (event.type() == NotificationType.DREAM_RECEIVED) {
                model.putIfAbsent(MODEL_WAIT_MESSAGE, waitTimeView.message(user.getLocale()));
            }
            Object ref = event.args().get(NotificationService.ARG_REF);
            String emailRef = ref != null ? String.valueOf(ref) : String.valueOf(event.notificationId());
            mailService.send(user.getId(), user.getEmail(), template.get(), user.getLocale(), model, emailRef);
        } catch (RuntimeException e) {
            log.warn("E-mail for notification {} failed", event.notificationId(), e);
        }
    }
}
