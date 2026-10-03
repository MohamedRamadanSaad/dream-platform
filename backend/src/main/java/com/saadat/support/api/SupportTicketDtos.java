package com.saadat.support.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.saadat.common.domain.EmailStatus;
import com.saadat.support.domain.SupportTicketAction;
import com.saadat.support.domain.SupportTicketStatus;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Bodies of the /admin/support/tickets routes (frontend types.ts SupportTicket*). */
public final class SupportTicketDtos {

    private SupportTicketDtos() {
    }

    /**
     * types.ts SupportTicket — one row of the list. {@code lastMessage}/{@code lastMessageAt}: the interpreter's latest
     * message (null while NEW); {@code eventsCount}: how many messages she sent on this ticket; {@code body}: the
     * plain text the person wrote (null until it arrives).
     */
    public record SupportTicketRow(UUID id, long number, String fromEmail, String fromName, String subject,
                                   Instant receivedAt, SupportTicketStatus status, Instant updatedAt,
                                   Instant closedAt, String lastMessage, Instant lastMessageAt, int eventsCount,
                                   String body) {
    }

    /** types.ts SupportTicketEvent — one message of the interpreter, oldest first in the detail. */
    public record SupportTicketEventDto(UUID id, SupportTicketAction action, String message, Instant createdAt,
                                        String actorName, EmailStatus emailStatus) {
    }

    /** types.ts SupportTicketDetail — the row fields plus the history. */
    public record SupportTicketDetail(UUID id, long number, String fromEmail, String fromName, String subject,
                                      Instant receivedAt, SupportTicketStatus status, Instant updatedAt,
                                      Instant closedAt, String lastMessage, Instant lastMessageAt, int eventsCount,
                                      String body, List<SupportTicketEventDto> events) {
    }

    /** types.ts SupportTicketCounts {@code {new, inProgress, closed}} ("new" is a Java keyword). */
    public record SupportTicketCounts(@JsonProperty("new") long newCount, long inProgress, long closed) {
    }

    /** {@code {message}} of POST in-progress / close (trimmed, 1..2000 characters; the service checks the length). */
    public record MessageRequest(@NotBlank String message) {
    }
}
