package com.saadat.support.repo;

import com.saadat.support.domain.SupportTicket;
import com.saadat.support.domain.SupportTicketStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface SupportTicketRepository extends JpaRepository<SupportTicket, UUID> {

    /** Next human ticket number (sequence support_ticket_number_seq; never in a read-only transaction). */
    @Transactional
    @Query(value = "select nextval('support_ticket_number_seq')", nativeQuery = true)
    Long nextNumber();

    Optional<SupportTicket> findByMessageId(String messageId);

    /** Repeated delivery without a Message-ID: same sender + subject recorded after {@code since}. */
    boolean existsByFromEmailAndSubjectAndCreatedAtAfter(String fromEmail, String subject, Instant since);

    /** Same as {@link #existsByFromEmailAndSubjectAndCreatedAtAfter} for a message without a subject. */
    boolean existsByFromEmailAndSubjectIsNullAndCreatedAtAfter(String fromEmail, Instant since);

    Page<SupportTicket> findByStatusOrderByReceivedAtDescNumberDesc(SupportTicketStatus status, Pageable pageable);

    Page<SupportTicket> findAllByOrderByReceivedAtDescNumberDesc(Pageable pageable);

    long countByStatus(SupportTicketStatus status);

    /** {@code SELECT ... FOR UPDATE}: serializes the interpreter's actions on one ticket. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from SupportTicket t where t.id = :id")
    Optional<SupportTicket> findByIdForUpdate(@Param("id") UUID id);
}
