package com.saadat.notifications.repo;

import com.saadat.common.domain.EmailStatus;
import com.saadat.notifications.domain.EmailLog;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmailLogRepository extends JpaRepository<EmailLog, UUID> {

    /** "Send once" guard, e.g. existsByTemplateAndRef("reply-reminder", dreamId.toString()). */
    boolean existsByTemplateAndRef(String template, String ref);

    List<EmailLog> findByToEmailOrderByCreatedAtDesc(String toEmail);

    List<EmailLog> findByTemplateOrderByCreatedAtDesc(String template);

    long countByStatus(EmailStatus status);
}
