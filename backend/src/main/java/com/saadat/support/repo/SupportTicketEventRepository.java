package com.saadat.support.repo;

import com.saadat.support.domain.SupportTicketEvent;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SupportTicketEventRepository extends JpaRepository<SupportTicketEvent, UUID> {

    /** History of one ticket, oldest first. */
    List<SupportTicketEvent> findByTicketIdOrderByCreatedAtAsc(UUID ticketId);

    /** Events of a page of tickets, newest first (latest message + count per ticket). */
    List<SupportTicketEvent> findByTicketIdInOrderByCreatedAtDesc(Collection<UUID> ticketIds);
}
