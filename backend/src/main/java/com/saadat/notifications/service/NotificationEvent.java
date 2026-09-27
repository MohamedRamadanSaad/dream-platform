package com.saadat.notifications.service;

import com.saadat.common.domain.NotificationType;
import java.util.Map;
import java.util.UUID;

/** A persisted notification handed to the async dispatcher (push + e-mail) after commit. */
public record NotificationEvent(
        UUID notificationId,
        UUID userId,
        NotificationType type,
        String title,
        String body,
        String link,
        Map<String, Object> args) {
}
