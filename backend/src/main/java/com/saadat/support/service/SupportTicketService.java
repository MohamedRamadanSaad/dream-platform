package com.saadat.support.service;

import com.saadat.common.api.PageResponse;
import com.saadat.common.api.Pages;
import com.saadat.common.audit.AuditService;
import com.saadat.common.domain.EmailStatus;
import com.saadat.common.domain.Locale;
import com.saadat.common.error.ApiException;
import com.saadat.common.error.ConflictException;
import com.saadat.common.error.NotFoundException;
import com.saadat.common.web.LogMask;
import com.saadat.mail.MailService;
import com.saadat.mail.MailTemplates;
import com.saadat.mail.inbound.InboundMailParser.InboundMessage;
import com.saadat.support.api.SupportTicketDtos.SupportTicketCounts;
import com.saadat.support.api.SupportTicketDtos.SupportTicketDetail;
import com.saadat.support.api.SupportTicketDtos.SupportTicketEventDto;
import com.saadat.support.api.SupportTicketDtos.SupportTicketRow;
import com.saadat.support.domain.SupportTicket;
import com.saadat.support.domain.SupportTicketAction;
import com.saadat.support.domain.SupportTicketEvent;
import com.saadat.support.domain.SupportTicketStatus;
import com.saadat.support.repo.SupportTicketEventRepository;
import com.saadat.support.repo.SupportTicketRepository;
import com.saadat.users.domain.User;
import com.saadat.users.repo.UserRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Support tickets: one per e-mail a person sent to the support mailbox (opened by the inbound-mail webhook after the
 * guards of SupportAutoReplyService#screen), handled by the interpreter from the dashboard.
 *
 * <ul>
 *   <li>{@link #openFromInbound}: de-duplicates by Message-ID, or (no Message-ID) by the same sender + subject within
 *       {@link #DEDUPE_WINDOW}; never stores the body; never throws.</li>
 *   <li>{@link #markInProgress} (NEW / IN_PROGRESS → IN_PROGRESS, repeatable) and {@link #close} (NEW / IN_PROGRESS →
 *       CLOSED, final; 409 {@code TICKET_CLOSED} otherwise): the status change, the event and the audit row commit
 *       first, then the e-mail to the sender is sent synchronously (template {@code support-in-progress} /
 *       {@code support-closed}, as a reply in the sender's thread when the Message-ID is known) and its outcome is
 *       stored on the event. The action succeeds whatever the e-mail outcome (LOGGED without SMTP).</li>
 * </ul>
 */
@Slf4j
@Service
public class SupportTicketService {

    public static final int MESSAGE_MAX = 2000;
    public static final String CODE_TICKET_CLOSED = "TICKET_CLOSED";
    public static final String CODE_MESSAGE_TOO_LONG = "MESSAGE_TOO_LONG";
    public static final String CODE_MESSAGE_REQUIRED = "MESSAGE_REQUIRED";

    static final Duration DEDUPE_WINDOW = Duration.ofMinutes(2);
    /** A payload date further in the future than this (clock skew) is ignored; so is one before 2000. */
    private static final Duration FUTURE_TOLERANCE = Duration.ofDays(1);
    private static final Instant OLDEST_DATE = Instant.parse("2000-01-01T00:00:00Z");
    private static final int EMAIL_MAX = 320;
    private static final int NAME_MAX = 200;
    private static final int SUBJECT_MAX = 500;
    private static final int MESSAGE_ID_MAX = 500;

    private final SupportTicketRepository ticketRepository;
    private final SupportTicketEventRepository eventRepository;
    private final UserRepository userRepository;
    private final MailService mailService;
    private final AuditService auditService;
    private final Clock clock;
    private final TransactionTemplate tx;

    public SupportTicketService(SupportTicketRepository ticketRepository, SupportTicketEventRepository eventRepository,
                                UserRepository userRepository, MailService mailService, AuditService auditService,
                                Clock clock, PlatformTransactionManager transactionManager) {
        this.ticketRepository = ticketRepository;
        this.eventRepository = eventRepository;
        this.userRepository = userRepository;
        this.mailService = mailService;
        this.auditService = auditService;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactionManager);
    }

    // ================================================================== inbound

    /**
     * Opens a NEW ticket for an e-mail a person sent (the caller has already applied the guards). Empty when the
     * message has no sender or is a repeated delivery. Never throws: a failure is logged and the webhook goes on.
     */
    public Optional<SupportTicket> openFromInbound(InboundMessage message) {
        String sender = clean(message == null ? null : message.sender(), EMAIL_MAX);
        if (sender == null) {
            return Optional.empty();
        }
        try {
            String email = sender.toLowerCase(java.util.Locale.ROOT);
            String subject = clean(message.subject(), SUBJECT_MAX);
            String messageId = clean(message.messageId(), MESSAGE_ID_MAX);
            Instant now = clock.instant();
            if (messageId != null) {
                if (ticketRepository.findByMessageId(messageId).isPresent()) {
                    log.info("Support ticket already exists for this message id, repeated delivery ignored");
                    return Optional.empty();
                }
            } else {
                Instant since = now.minus(DEDUPE_WINDOW);
                boolean duplicate = subject == null
                        ? ticketRepository.existsByFromEmailAndSubjectIsNullAndCreatedAtAfter(email, since)
                        : ticketRepository.existsByFromEmailAndSubjectAndCreatedAtAfter(email, subject, since);
                if (duplicate) {
                    log.info("Support ticket for the same sender and subject opened moments ago, ignored");
                    return Optional.empty();
                }
            }
            SupportTicket ticket = new SupportTicket();
            ticket.setNumber(ticketRepository.nextNumber());
            ticket.setFromEmail(email);
            ticket.setFromName(clean(message.senderName(), NAME_MAX));
            ticket.setSubject(subject);
            ticket.setMessageId(messageId);
            ticket.setReceivedAt(receivedAt(message.receivedAt(), now));
            ticket.setStatus(SupportTicketStatus.NEW);
            ticket.setCreatedAt(now);
            ticket.setUpdatedAt(now);
            SupportTicket saved = ticketRepository.saveAndFlush(ticket);
            log.info("Support ticket #{} opened for {}", saved.getNumber(), LogMask.email(email));
            return Optional.of(saved);
        } catch (DataIntegrityViolationException e) {
            log.info("Support ticket not opened: the same message was stored concurrently");
            return Optional.empty();
        } catch (RuntimeException e) {
            log.error("Support ticket could not be opened for {}", LogMask.email(sender), e);
            return Optional.empty();
        }
    }

    private Instant receivedAt(Instant fromPayload, Instant now) {
        if (fromPayload == null || fromPayload.isBefore(OLDEST_DATE) || fromPayload.isAfter(now.plus(FUTURE_TOLERANCE))) {
            return now;
        }
        return fromPayload;
    }

    // ================================================================== queries

    /** Newest first; without a status every ticket. */
    @Transactional(readOnly = true)
    public PageResponse<SupportTicketRow> list(SupportTicketStatus status, Integer page, Integer size) {
        Pageable pageable = Pages.of(page, size);
        Page<SupportTicket> tickets = status == null
                ? ticketRepository.findAllByOrderByReceivedAtDescNumberDesc(pageable)
                : ticketRepository.findByStatusOrderByReceivedAtDescNumberDesc(status, pageable);
        List<UUID> ids = tickets.getContent().stream().map(SupportTicket::getId).toList();
        Map<UUID, List<SupportTicketEvent>> eventsByTicket = ids.isEmpty() ? Map.of()
                : eventRepository.findByTicketIdInOrderByCreatedAtDesc(ids).stream()
                        .collect(Collectors.groupingBy(SupportTicketEvent::getTicketId));
        return PageResponse.from(tickets, t -> {
            List<SupportTicketEvent> events = eventsByTicket.getOrDefault(t.getId(), List.of());
            SupportTicketEvent latest = events.isEmpty() ? null : events.get(0);
            return row(t, latest, events.size());
        });
    }

    @Transactional(readOnly = true)
    public SupportTicketCounts counts() {
        return new SupportTicketCounts(ticketRepository.countByStatus(SupportTicketStatus.NEW),
                ticketRepository.countByStatus(SupportTicketStatus.IN_PROGRESS),
                ticketRepository.countByStatus(SupportTicketStatus.CLOSED));
    }

    @Transactional(readOnly = true)
    public SupportTicketDetail detail(UUID id) {
        SupportTicket t = ticketRepository.findById(id).orElseThrow(() -> NotFoundException.of("Support ticket", id));
        List<SupportTicketEvent> events = eventRepository.findByTicketIdOrderByCreatedAtAsc(t.getId());
        List<UUID> actorIds = events.stream().map(SupportTicketEvent::getActorId).filter(Objects::nonNull)
                .distinct().toList();
        Map<UUID, String> names = actorIds.isEmpty() ? Map.of()
                : userRepository.findAllById(actorIds).stream()
                        .collect(Collectors.toMap(User::getId, u -> u.getName() == null ? "" : u.getName()));
        List<SupportTicketEventDto> eventDtos = events.stream()
                .map(e -> new SupportTicketEventDto(e.getId(), e.getAction(), e.getMessage(), e.getCreatedAt(),
                        e.getActorId() == null ? null : names.get(e.getActorId()), e.getEmailStatus()))
                .toList();
        SupportTicketEvent latest = events.isEmpty() ? null : events.get(events.size() - 1);
        SupportTicketRow r = row(t, latest, events.size());
        return new SupportTicketDetail(r.id(), r.number(), r.fromEmail(), r.fromName(), r.subject(), r.receivedAt(),
                r.status(), r.updatedAt(), r.closedAt(), r.lastMessage(), r.lastMessageAt(), r.eventsCount(),
                eventDtos);
    }

    private static SupportTicketRow row(SupportTicket t, SupportTicketEvent latest, int eventsCount) {
        return new SupportTicketRow(t.getId(), t.getNumber(), t.getFromEmail(), t.getFromName(), t.getSubject(),
                t.getReceivedAt(), t.getStatus(), t.getUpdatedAt(), t.getClosedAt(),
                latest == null ? null : latest.getMessage(), latest == null ? null : latest.getCreatedAt(),
                eventsCount);
    }

    // ================================================================== actions

    /** NEW / IN_PROGRESS → IN_PROGRESS with a message e-mailed to the sender; 409 when the ticket is closed. */
    public SupportTicketDetail markInProgress(UUID actor, UUID ticketId, String message) {
        return act(actor, ticketId, SupportTicketAction.IN_PROGRESS, message);
    }

    /** NEW / IN_PROGRESS → CLOSED (final) with a message e-mailed to the sender; 409 when already closed. */
    public SupportTicketDetail close(UUID actor, UUID ticketId, String message) {
        return act(actor, ticketId, SupportTicketAction.CLOSED, message);
    }

    /** What the e-mail needs once the change is committed. */
    private record Committed(UUID eventId, String toEmail, long number, String subject, String messageId) {
    }

    private SupportTicketDetail act(UUID actor, UUID ticketId, SupportTicketAction action, String rawMessage) {
        String message = normalizeMessage(rawMessage);
        Committed committed = tx.execute(status -> {
            SupportTicket t = ticketRepository.findByIdForUpdate(ticketId)
                    .orElseThrow(() -> NotFoundException.of("Support ticket", ticketId));
            if (t.getStatus() == SupportTicketStatus.CLOSED) {
                throw new ConflictException("The ticket is already closed", CODE_TICKET_CLOSED);
            }
            Instant now = clock.instant();
            SupportTicketStatus before = t.getStatus();
            SupportTicketStatus after = action == SupportTicketAction.CLOSED
                    ? SupportTicketStatus.CLOSED : SupportTicketStatus.IN_PROGRESS;
            t.setStatus(after);
            t.setUpdatedAt(now);
            t.setLastActionBy(actor);
            if (after == SupportTicketStatus.CLOSED) {
                t.setClosedAt(now);
            }
            ticketRepository.save(t);

            SupportTicketEvent event = new SupportTicketEvent();
            event.setTicketId(t.getId());
            event.setAction(action);
            event.setMessage(message);
            event.setActorId(actor);
            event.setCreatedAt(now);
            SupportTicketEvent savedEvent = eventRepository.save(event);

            auditService.record(actor,
                    action == SupportTicketAction.CLOSED ? "SUPPORT_TICKET_CLOSE" : "SUPPORT_TICKET_IN_PROGRESS",
                    "support_tickets", t.getId().toString(), Map.of("status", before.name()),
                    Map.of("status", after.name(), "number", t.getNumber()));
            return new Committed(savedEvent.getId(), t.getFromEmail(), t.getNumber(), t.getSubject(),
                    t.getMessageId());
        });
        if (committed == null) {
            throw new IllegalStateException("Support ticket action returned no result");
        }

        String template = action == SupportTicketAction.CLOSED
                ? MailTemplates.SUPPORT_CLOSED : MailTemplates.SUPPORT_IN_PROGRESS;
        EmailStatus emailStatus = mailService.send(committed.toEmail(), template, Locale.AR,
                mailModel(committed.number(), committed.subject(), message, committed.messageId()),
                ticketId.toString());
        try {
            tx.executeWithoutResult(status -> eventRepository.findById(committed.eventId()).ifPresent(e -> {
                e.setEmailStatus(emailStatus);
                eventRepository.save(e);
            }));
        } catch (RuntimeException e) {
            log.error("Could not store the e-mail outcome of support ticket #{}", committed.number(), e);
        }
        log.info("Support ticket #{} → {} (e-mail {})", committed.number(), action, emailStatus);
        return detail(ticketId);
    }

    /**
     * Model of the {@code support-in-progress} / {@code support-closed} e-mails: ticketNumber, ticketSubject,
     * messageLines (rendered escaped, one {@code <br>} between lines) and the Message-ID to answer.
     */
    public static Map<String, Object> mailModel(long number, String subject, String message, String inReplyTo) {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("ticketNumber", String.valueOf(number));
        model.put("ticketSubject", subject == null ? "" : subject);
        model.put("messageLines", Arrays.asList((message == null ? "" : message).split("\n", -1)));
        if (inReplyTo != null && !inReplyTo.isBlank()) {
            model.put(MailService.MODEL_IN_REPLY_TO, inReplyTo);
        }
        return model;
    }

    /** CRLF/CR → LF, trimmed; 400 when empty or longer than {@link #MESSAGE_MAX}. */
    static String normalizeMessage(String raw) {
        String message = raw == null ? "" : raw.replace("\r\n", "\n").replace('\r', '\n').strip();
        if (message.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "bad-request", CODE_MESSAGE_REQUIRED,
                    "The message is required");
        }
        if (message.length() > MESSAGE_MAX) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "bad-request", CODE_MESSAGE_TOO_LONG,
                    "The message must be at most " + MESSAGE_MAX + " characters");
        }
        return message;
    }

    /** Trimmed, control characters removed, cut to {@code max}; null when empty. */
    private static String clean(String value, int max) {
        if (value == null) {
            return null;
        }
        String v = value.replaceAll("\\p{Cntrl}", " ").trim();
        if (v.isEmpty()) {
            return null;
        }
        return v.length() > max ? v.substring(0, max) : v;
    }
}
