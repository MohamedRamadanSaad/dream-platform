package com.saadat.notifications.api;

import com.saadat.common.domain.NotificationType;
import com.saadat.notifications.domain.Notification;
import java.time.Instant;
import java.util.UUID;

/** types.ts {@code NotificationDto}. */
public record NotificationDto(
        UUID id,
        NotificationType type,
        String title,
        String body,
        String link,
        Instant createdAt,
        Instant readAt) {

    public static NotificationDto from(Notification n) {
        return new NotificationDto(n.getId(), n.getType(), n.getTitle(), n.getBody(), n.getLink(), n.getCreatedAt(),
                n.getReadAt());
    }
}
