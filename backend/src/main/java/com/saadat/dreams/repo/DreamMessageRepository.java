package com.saadat.dreams.repo;

import com.saadat.common.domain.Role;
import com.saadat.dreams.domain.DreamMessage;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface DreamMessageRepository extends JpaRepository<DreamMessage, UUID> {

    List<DreamMessage> findByDreamIdOrderByCreatedAtAsc(UUID dreamId);

    /** DreamSummary.unreadMessages for the user = unread INTERPRETER messages. */
    long countByDreamIdAndSenderRoleAndReadAtIsNull(UUID dreamId, Role senderRole);

    /** Marks messages from {@code senderRole} as read (user GET marks INTERPRETER msgs; admin GET marks USER msgs). */
    @Modifying
    @Transactional
    @Query("update DreamMessage m set m.readAt = :now "
            + "where m.dreamId = :dreamId and m.senderRole = :senderRole and m.readAt is null")
    int markRead(@Param("dreamId") UUID dreamId, @Param("senderRole") Role senderRole, @Param("now") Instant now);
}
