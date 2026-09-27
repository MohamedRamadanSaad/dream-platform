package com.saadat.common.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.saadat.common.audit.domain.AuditLog;
import com.saadat.common.audit.repo.AuditLogRepository;
import java.time.Clock;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records every interpreter/admin mutation (spec §9, contract rule 8). Joins the caller's transaction so
 * the audit row commits or rolls back together with the change.
 *
 * <p>Conventions: {@code action} is UPPER_SNAKE (e.g. SETTING_UPDATE, PRICE_RULE_UPSERT, CREDITS_ADJUST),
 * {@code entity} is the table name (e.g. app_settings, price_rules), {@code before}/{@code after} are any
 * Jackson-serializable objects (DTO records preferred over entities) or null.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditLogRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Transactional(propagation = Propagation.REQUIRED)
    public AuditLog record(UUID actorId, String action, String entity, String entityId, Object before, Object after) {
        AuditLog row = new AuditLog();
        row.setActorId(actorId);
        row.setAction(action);
        row.setEntity(entity);
        row.setEntityId(entityId);
        row.setBefore(toJson(before));
        row.setAfter(toJson(after));
        row.setCreatedAt(clock.instant());
        return repository.save(row);
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize audit payload of type " + value.getClass().getName(), e);
        }
    }
}
