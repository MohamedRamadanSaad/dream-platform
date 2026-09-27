package com.saadat.common.audit.repo;

import com.saadat.common.audit.domain.AuditLog;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

    List<AuditLog> findByEntityAndEntityIdOrderByCreatedAtDesc(String entity, String entityId);

    Page<AuditLog> findAllByOrderByCreatedAtDesc(Pageable pageable);

    List<AuditLog> findByActionOrderByCreatedAtDesc(String action);
}
