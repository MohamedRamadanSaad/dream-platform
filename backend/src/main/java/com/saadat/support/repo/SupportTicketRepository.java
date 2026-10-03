package com.saadat.support.repo;

import com.saadat.support.domain.SupportTicket;
import com.saadat.support.domain.SupportTicketStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
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

    /**
     * Tickets still without text, received after {@code since}, never looked for or last looked for before
     * {@code retryBefore}; newest first (the backfill job passes a page of 20).
     */
    @Query("select t from SupportTicket t where t.body is null and t.receivedAt > :since"
            + " and (t.bodyFetchedAt is null or t.bodyFetchedAt < :retryBefore)"
            + " order by t.receivedAt desc, t.number desc")
    List<SupportTicket> findBodyMissing(@Param("since") Instant since, @Param("retryBefore") Instant retryBefore,
                                        Pageable pageable);

    /** Stores the text unless one is already there (targeted update: never overwrites the status). */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update SupportTicket t set t.body = :body, t.bodyFetchedAt = :at where t.id = :id and t.body is null")
    int storeBody(@Param("id") UUID id, @Param("body") String body, @Param("at") Instant at);

    /** Records a fetch attempt that found no text. */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update SupportTicket t set t.bodyFetchedAt = :at where t.id = :id and t.body is null")
    int markBodyFetchAttempt(@Param("id") UUID id, @Param("at") Instant at);
}
