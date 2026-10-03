package com.saadat.support.service;

import com.saadat.mail.inbound.InboxMessageReader;
import com.saadat.settings.SettingKeys;
import com.saadat.settings.SettingsService;
import com.saadat.support.domain.SupportTicket;
import com.saadat.support.repo.SupportTicketRepository;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Fills {@code support_tickets.body} when the inbound webhook did not carry the text: reads the e-mail read-only from
 * the mailbox's INBOX over IMAP ({@link InboxMessageReader}).
 *
 * <ul>
 *   <li>{@link #scheduleFetch}: right after a ticket is opened without text — up to {@link #RETRY_DELAYS} attempts
 *       (5 s, 30 s, 120 s) on a private single background thread, so the webhook answer and the shared async pool
 *       are never held up; stops at the first success</li>
 *   <li>{@link #backfill}: every 10 minutes, up to {@link #BACKFILL_BATCH} tickets received in the last
 *       {@link #BACKFILL_WINDOW} still without text, never looked for or last looked for over
 *       {@link #RETRY_AFTER} ago</li>
 *   <li>{@link #fetchNow}: the interpreter's «تحميل الرسالة» button (synchronous)</li>
 * </ul>
 *
 * <p>Off when the BOOL setting {@code support.fetch_body} is false or IMAP is not configured (no SMTP account — e.g.
 * tests). Never throws; logs never contain the text.
 */
@Slf4j
@Service
public class SupportTicketBodyService {

    static final List<Duration> RETRY_DELAYS = List.of(Duration.ofSeconds(5), Duration.ofSeconds(30),
            Duration.ofSeconds(120));
    static final int BACKFILL_BATCH = 20;
    static final Duration BACKFILL_WINDOW = Duration.ofDays(30);
    static final Duration RETRY_AFTER = Duration.ofHours(1);

    private final SupportTicketRepository ticketRepository;
    private final InboxMessageReader reader;
    private final SettingsService settings;
    private final Clock clock;
    private final ScheduledExecutorService scheduler;

    public SupportTicketBodyService(SupportTicketRepository ticketRepository, InboxMessageReader reader,
                                    SettingsService settings, Clock clock) {
        this.ticketRepository = ticketRepository;
        this.reader = reader;
        this.settings = settings;
        this.clock = clock;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "support-body");
            thread.setDaemon(true);
            return thread;
        });
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }

    /** Whether fetching over IMAP is switched on and possible. */
    public boolean enabled() {
        return reader.isConfigured() && settings.getBool(SettingKeys.SUPPORT_FETCH_BODY, true);
    }

    /** Queues the background attempts for a ticket just opened without text. Returns immediately; never throws. */
    public void scheduleFetch(UUID ticketId) {
        try {
            if (ticketId == null || !enabled()) {
                return;
            }
            scheduleAttempt(ticketId, 0);
        } catch (RuntimeException e) {
            log.warn("Could not queue the text fetch of a support ticket: {}", e.getMessage());
        }
    }

    private void scheduleAttempt(UUID ticketId, int index) {
        if (index >= RETRY_DELAYS.size()) {
            return;
        }
        try {
            scheduler.schedule(() -> {
                boolean done = attempt(ticketId);
                if (!done) {
                    scheduleAttempt(ticketId, index + 1);
                }
            }, RETRY_DELAYS.get(index).toMillis(), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            log.debug("Support text fetch not queued (shutting down)");
        }
    }

    /** One attempt; true when there is nothing more to do (text stored, already there, ticket gone, or switched off). */
    boolean attempt(UUID ticketId) {
        try {
            if (!enabled()) {
                return true;
            }
            Optional<SupportTicket> found = ticketRepository.findById(ticketId);
            if (found.isEmpty() || found.get().getBody() != null) {
                return true;
            }
            return fetchInto(found.get());
        } catch (RuntimeException e) {
            log.warn("Support ticket text fetch failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Synchronous fetch for one ticket (the dashboard button): does nothing when the text is already there or
     * fetching is off. Never throws; the caller returns the ticket detail either way.
     */
    public void fetchNow(UUID ticketId) {
        try {
            if (!enabled()) {
                return;
            }
            ticketRepository.findById(ticketId).filter(t -> t.getBody() == null).ifPresent(this::fetchInto);
        } catch (RuntimeException e) {
            log.warn("Support ticket text fetch failed: {}", e.getMessage());
        }
    }

    /** Every 10 minutes (first run 3 minutes after start): tickets still without text. */
    @Scheduled(fixedDelayString = "PT10M", initialDelayString = "PT3M")
    public void backfill() {
        try {
            if (!enabled()) {
                return;
            }
            Instant now = clock.instant();
            List<SupportTicket> missing = ticketRepository.findBodyMissing(now.minus(BACKFILL_WINDOW),
                    now.minus(RETRY_AFTER), PageRequest.of(0, BACKFILL_BATCH));
            int filled = 0;
            for (SupportTicket t : missing) {
                if (fetchInto(t)) {
                    filled++;
                }
            }
            if (!missing.isEmpty()) {
                log.info("Support text backfill: {} of {} tickets filled", filled, missing.size());
            }
        } catch (RuntimeException e) {
            log.error("Support text backfill failed", e);
        }
    }

    /** Reads the text and stores it, or records the attempt; true when the text was stored. */
    private boolean fetchInto(SupportTicket ticket) {
        Optional<String> text = reader.fetchText(ticket.getMessageId(), ticket.getFromEmail(), ticket.getSubject());
        Instant now = clock.instant();
        if (text.isPresent()) {
            int updated = ticketRepository.storeBody(ticket.getId(), text.get(), now);
            log.info("Support ticket #{}: message text {} ({} characters)", ticket.getNumber(),
                    updated > 0 ? "stored" : "already present", text.get().length());
            return true;
        }
        ticketRepository.markBodyFetchAttempt(ticket.getId(), now);
        return false;
    }
}
