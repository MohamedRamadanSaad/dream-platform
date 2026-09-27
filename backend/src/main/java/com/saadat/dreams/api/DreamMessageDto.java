package com.saadat.dreams.api;

import com.saadat.common.domain.Role;
import com.saadat.dreams.domain.DreamMessage;
import java.time.Instant;
import java.util.UUID;

/** types.ts DreamMessage. */
public record DreamMessageDto(UUID id, Role senderRole, String body, Instant createdAt, Instant readAt) {

    public static DreamMessageDto from(DreamMessage m) {
        return new DreamMessageDto(m.getId(), m.getSenderRole(), m.getBody(), m.getCreatedAt(), m.getReadAt());
    }
}
