package com.saadat.notifications.repo;

import com.saadat.common.domain.EmailStatus;
import com.saadat.notifications.domain.EmailLog;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmailLogRepository extends JpaRepository<EmailLog, UUID> {

    /** "Send once" guard, e.g. existsByTemplateAndRef("reply-reminder", dreamId.toString()). */
    boolean existsByTemplateAndRef(String template, String ref);

    /** E-mails of one kind recorded since {@code since} (daily caps). */
    long countByTemplateAndCreatedAtAfter(String template, java.time.Instant since);

    /** Whether any e-mail with a ref starting with {@code prefix} exists (an announcement already started). */
    boolean existsByTemplateAndRefStartingWith(String template, String prefix);

    List<EmailLog> findByToEmailOrderByCreatedAtDesc(String toEmail);

    /** Cooldown guard, e.g. "was support-auto-reply already sent to this address in the last 24 h?". */
    boolean existsByToEmailIgnoreCaseAndTemplateAndCreatedAtAfter(String toEmail, String template, Instant after);

    List<EmailLog> findByTemplateOrderByCreatedAtDesc(String template);

    long countByStatus(EmailStatus status);
}
