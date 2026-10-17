package com.saadat.dreams.service;

import com.saadat.common.api.PageResponse;
import com.saadat.common.api.Pages;
import com.saadat.common.audit.AuditService;
import com.saadat.common.domain.DreamStatus;
import com.saadat.common.domain.Role;
import com.saadat.common.error.ApiException;
import com.saadat.common.error.ConflictException;
import com.saadat.common.error.NotFoundException;
import com.saadat.common.error.PaymentRequiredException;
import com.saadat.common.error.Problems;
import com.saadat.common.error.ValidationException;
import com.saadat.credits.service.CreditService;
import com.saadat.dreams.api.DreamDetailDto;
import com.saadat.dreams.api.DreamMessageDto;
import com.saadat.dreams.api.DreamRequests;
import com.saadat.dreams.api.DreamSummaryDto;
import com.saadat.dreams.domain.Dream;
import com.saadat.dreams.domain.DreamMessage;
import com.saadat.dreams.domain.Testimonial;
import com.saadat.dreams.repo.DreamMessageRepository;
import com.saadat.dreams.repo.DreamRepository;
import com.saadat.dreams.repo.TestimonialRepository;
import com.saadat.mail.EventMailer;
import com.saadat.mail.FrontendPaths;
import com.saadat.mail.MailService;
import com.saadat.mail.MailTemplates;
import com.saadat.dreams.api.DreamPaymentLink;
import com.saadat.payments.domain.CreditLedgerEntry;
import com.saadat.payments.service.DreamAttachments;
import com.saadat.settings.SettingKeys;
import com.saadat.settings.SettingsService;
import com.saadat.users.domain.User;
import com.saadat.users.repo.UserRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The dream owner's side of the status machine (contract rules 2, 4–7):
 * DRAFT → IN_REVIEW ⇄ AWAITING_USER_REPLY → INTERPRETED; CANCELLED refunds (see AdminDreamService).
 *
 * <p>Lock order everywhere: user row (credits) → dream rows. Submitting never partially succeeds.
 */
@Service
@RequiredArgsConstructor
public class DreamService {

    private static final int HOURS_PER_DAY = 24;

    private final DreamRepository dreamRepository;
    private final DreamMessageRepository messageRepository;
    private final TestimonialRepository testimonialRepository;
    private final CreditService creditService;
    private final SettingsService settingsService;
    private final DreamMapper mapper;
    private final DreamNotifier notifier;
    private final UserRepository userRepository;
    private final EventMailer eventMailer;
    private final DreamAttachments attachments;
    private final AuditService auditService;
    private final Clock clock;

    /** Virtual list filter: drafts attached to a payment under review or rejected (contract §2). */
    public static final String FILTER_PENDING_PAYMENT = "PENDING_PAYMENT";
    public static final String FILTER_ALL = "ALL";
    public static final String CODE_ATTACHED_TO_PAYMENT = "ATTACHED_TO_PAYMENT";
    /** At most one DREAM_EDITED notice per dream in this interval. */
    static final Duration EDIT_NOTICE_INTERVAL = Duration.ofHours(1);

    // ================================================================== drafts

    @Transactional
    public DreamDetailDto createDraft(UUID userId, DreamRequests.DraftRequest req) {
        String text = validText(req.text());
        long drafts = dreamRepository.countByUserIdAndStatus(userId, DreamStatus.DRAFT);
        if (drafts >= settingsService.getInt(SettingKeys.DREAMS_DRAFT_LIMIT)) {
            throw new ValidationException("Draft limit reached", "DRAFT_LIMIT");
        }
        Instant now = clock.instant();
        Dream d = new Dream();
        d.setUserId(userId);
        d.setGender(req.gender());
        d.setStatus(DreamStatus.DRAFT);
        d.setText(text);
        d.setCreatedAt(now);
        d.setUpdatedAt(now);
        dreamRepository.save(d);
        return mapper.detail(d);
    }

    /**
     * Edits the text: a DRAFT, or a submitted dream (IN_REVIEW or AWAITING_USER_REPLY) the interpreter has not started
     * yet (409 NOT_EDITABLE otherwise). The row is locked, so an edit and "Start interpreting" cannot race. Editing a
     * submitted dream sets {@code userEditedAt} and tells the interpreters (in-app, at most once per dream per hour).
     */
    @Transactional
    public DreamDetailDto updateDraft(UUID userId, UUID dreamId, DreamRequests.DraftRequest req) {
        Dream d = ownedForUpdate(userId, dreamId);
        if (!DreamMapper.editable(d)) {
            throw new ConflictException("This dream can no longer be edited", "NOT_EDITABLE");
        }
        d.setText(validText(req.text()));
        d.setGender(req.gender());
        if (d.getStatus() != DreamStatus.DRAFT) {
            Instant now = clock.instant();
            Instant previous = d.getUserEditedAt();
            d.setUserEditedAt(now);
            if (previous == null || !now.isBefore(previous.plus(EDIT_NOTICE_INTERVAL))) {
                notifier.edited(d.getId());
            }
        }
        dreamRepository.save(d);
        return mapper.detail(d);
    }

    @Transactional
    public void deleteDraft(UUID userId, UUID dreamId) {
        Dream d = ownedForUpdate(userId, dreamId);
        if (d.getStatus() != DreamStatus.DRAFT) {
            throw new ConflictException("Only drafts can be deleted", "NOT_DRAFT");
        }
        // held by a payment in progress or under review; a rejected payment lets it go
        attachments.requireNotHeld(userId, List.of(dreamId), null, CODE_ATTACHED_TO_PAYMENT);
        attachments.detachFromRejected(userId, List.of(dreamId), null);
        dreamRepository.delete(d);
    }

    // ================================================================== reads

    /**
     * The caller's dreams, newest first. {@code filter}: null/blank/ALL = every dream, a {@link DreamStatus} name, or
     * {@link #FILTER_PENDING_PAYMENT}. DRAFT leaves out the drafts attached to a payment; PENDING_PAYMENT lists only
     * those (contract §2). A user has few drafts (setting {@code dreams.draft_limit}), so drafts are paged in memory.
     */
    @Transactional(readOnly = true)
    public PageResponse<DreamSummaryDto> list(UUID userId, String filter, Integer page, Integer size) {
        Pageable pageable = Pages.of(page, size);
        String f = filter == null ? "" : filter.trim().toUpperCase(java.util.Locale.ROOT);
        if (f.isEmpty() || FILTER_ALL.equals(f)) {
            Page<Dream> dreams = dreamRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable);
            return PageResponse.of(mapper.summaries(userId, dreams.getContent()), dreams.getNumber(), dreams.getSize(),
                    dreams.getTotalElements());
        }
        if (FILTER_PENDING_PAYMENT.equals(f) || DreamStatus.DRAFT.name().equals(f)) {
            boolean attachedOnly = FILTER_PENDING_PAYMENT.equals(f);
            Map<UUID, DreamPaymentLink> links = attachments.attachedDrafts(userId);
            List<Dream> rows = new ArrayList<>(dreamRepository.findByUserIdAndStatusOrderByCreatedAtDesc(userId,
                            DreamStatus.DRAFT)
                    .stream()
                    .filter(d -> links.containsKey(d.getId()) == attachedOnly)
                    .toList());
            if (attachedOnly) {
                // dreams whose payment approval was taken back wait here too (V40)
                List<Dream> held = dreamRepository.findByUserIdAndStatusOrderByCreatedAtDesc(userId,
                        DreamStatus.AWAITING_PAYMENT);
                links.putAll(attachments.heldLinks(held));
                rows.addAll(held);
                rows.sort(java.util.Comparator.<Dream, Instant>comparing(Dream::getCreatedAt,
                        java.util.Comparator.nullsFirst(java.util.Comparator.<Instant>naturalOrder())).reversed());
            }
            int from = (int) Math.min((long) pageable.getPageNumber() * pageable.getPageSize(), rows.size());
            int to = Math.min(from + pageable.getPageSize(), rows.size());
            List<DreamSummaryDto> items = rows.subList(from, to).stream()
                    .map(d -> mapper.summary(d, links.get(d.getId())))
                    .toList();
            return PageResponse.of(items, pageable.getPageNumber(), pageable.getPageSize(), rows.size());
        }
        DreamStatus status;
        try {
            status = DreamStatus.valueOf(f);
        } catch (IllegalArgumentException e) {
            throw new ValidationException("Unknown status: " + filter, "INVALID_STATUS");
        }
        Page<Dream> dreams = dreamRepository.findByUserIdAndStatusOrderByCreatedAtDesc(userId, status, pageable);
        Map<UUID, DreamPaymentLink> held = status == DreamStatus.AWAITING_PAYMENT
                ? attachments.heldLinks(dreams.getContent()) : Map.of();
        Function<Dream, DreamSummaryDto> toDto = d -> mapper.summary(d, held.get(d.getId()));
        return PageResponse.from(dreams, toDto);
    }

    /** Owner view; marks the interpreter's messages as read. */
    @Transactional
    public DreamDetailDto get(UUID userId, UUID dreamId) {
        Dream d = owned(userId, dreamId);
        messageRepository.markRead(d.getId(), Role.INTERPRETER, clock.instant());
        return mapper.detail(d);
    }

    // ================================================================== submit

    /**
     * Submits the caller's drafts: all must be the caller's DRAFTs (else 409), credits are consumed
     * atomically (else 402 INSUFFICIENT_CREDITS, nothing submitted). The caller's AWAITING_PAYMENT dreams (their
     * payment approval was taken back, V40) are accepted too: they are resumed with the credits the user has (any
     * lot) and leave the held order.
     *
     * @param orderId order that paid for these credits (auto-submit after checkout), or null
     */
    @Transactional
    public DreamRequests.SubmitResponse submit(UUID userId, List<UUID> dreamIds, UUID orderId) {
        List<UUID> ids = distinct(dreamIds);
        if (ids.isEmpty()) {
            throw new ValidationException("dreamIds is required", "EMPTY_SUBMIT");
        }
        int balance = creditService.lockAndGetBalance(userId);
        List<Dream> dreams = dreamRepository.findAllByIdInAndUserIdForUpdate(ids, userId);
        if (dreams.size() != ids.size() || dreams.stream().anyMatch(d -> d.getStatus() != DreamStatus.DRAFT
                && d.getStatus() != DreamStatus.AWAITING_PAYMENT)) {
            throw new ConflictException("Some dreams are not your drafts", "NOT_DRAFT");
        }
        List<UUID> draftIds = dreams.stream().filter(d -> d.getStatus() == DreamStatus.DRAFT).map(Dream::getId)
                .toList();
        // a draft held by a payment in progress or under review is sent by that payment (contract §2); a rejected
        // payment lets it go: submitted with the credits the user has, it leaves that payment
        attachments.requireNotHeld(userId, draftIds, null, CODE_ATTACHED_TO_PAYMENT);
        if (balance < dreams.size()) {
            throw new PaymentRequiredException(dreams.size() - balance);
        }
        attachments.detachFromRejected(userId, draftIds, null);
        List<Dream> ordered = order(dreams, ids);
        List<UUID> submitted = new ArrayList<>();
        Instant now = clock.instant();
        for (Dream d : ordered) {
            if (d.getStatus() == DreamStatus.AWAITING_PAYMENT) {
                resume(d, creditService.consumeForDream(userId, d.getId()), now, userId);
                submitted.add(d.getId());
            }
        }
        List<Dream> drafts = ordered.stream().filter(d -> d.getStatus() == DreamStatus.DRAFT).toList();
        if (!drafts.isEmpty()) {
            submitted.addAll(doSubmit(userId, drafts, orderId));
        }
        // same (read-write) transaction and lock; the aggregate query auto-flushes the new ledger rows
        return new DreamRequests.SubmitResponse(submitted, creditService.lockAndGetBalance(userId));
    }

    /**
     * The order {@code orderId} was approved again after its approval had been taken back (V40): its AWAITING_PAYMENT
     * dreams resume, each paid with one credit of that order even when its credits are past their (first) expiry,
     * oldest submitted first; a dream stays held when no credit is left. No interpreter notification: the approval is
     * hers ({@code actor}, audited as DREAM_RESUMED). Joins the payment transaction; lock order user → dreams.
     */
    @Transactional
    public List<UUID> resumeHeld(UUID userId, UUID orderId, UUID actor) {
        List<UUID> ids = dreamRepository.findByHeldOrderIdAndStatusOrderBySubmittedAtAsc(orderId,
                DreamStatus.AWAITING_PAYMENT).stream().map(Dream::getId).toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        Instant now = clock.instant();
        List<UUID> resumed = new ArrayList<>();
        // lock order user → dreams (no expiry write-off before: this order's credits pay its held dreams)
        userRepository.findByIdForUpdate(userId).orElseThrow(() -> NotFoundException.of("User", userId));
        for (Dream d : order(dreamRepository.findAllByIdInAndUserIdForUpdate(ids, userId), ids)) {
            if (d.getStatus() != DreamStatus.AWAITING_PAYMENT || !orderId.equals(d.getHeldOrderId())) {
                continue;
            }
            CreditLedgerEntry entry = creditService.consumeForResume(userId, d.getId(), orderId);
            if (entry == null) {
                break;
            }
            resume(d, entry, now, actor);
            resumed.add(d.getId());
        }
        return resumed;
    }

    /**
     * AWAITING_PAYMENT → back to work, paid with {@code entry} (its SUBMIT debit): AWAITING_USER_REPLY when the
     * interpreter's question was still open (the SLA stays paused since she asked), else IN_PROGRESS when she had
     * started, IN_REVIEW otherwise, with {@code expectedBy} moved forward by the time it was held. Text, messages,
     * startedAt and expectedDays are kept; the hold is cleared. Audited as DREAM_RESUMED.
     */
    private void resume(Dream d, CreditLedgerEntry entry, Instant now, UUID actor) {
        UUID heldOrderId = d.getHeldOrderId();
        d.setLedgerEntryId(entry.getId());
        d.setHeldOrderId(null);
        d.setHeldAt(null);
        if (openQuestion(d)) {
            d.setStatus(DreamStatus.AWAITING_USER_REPLY);
            if (d.getSlaPausedAt() == null) {
                d.setSlaPausedAt(now);
            }
        } else {
            resumeSla(d, now);
            d.setStatus(d.getStartedAt() != null ? DreamStatus.IN_PROGRESS : DreamStatus.IN_REVIEW);
        }
        dreamRepository.save(d);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", d.getStatus().name());
        after.put("heldOrderId", heldOrderId == null ? "" : heldOrderId.toString());
        after.put("paidByOrderId", entry.getOrderId() == null ? "" : entry.getOrderId().toString());
        auditService.record(actor, "DREAM_RESUMED", "dreams", d.getId().toString(),
                Map.of("status", DreamStatus.AWAITING_PAYMENT.name()), after);
    }

    /** The last message of the dream is the interpreter's (a question the dreamer has not answered yet). */
    private boolean openQuestion(Dream d) {
        List<DreamMessage> messages = messageRepository.findByDreamIdOrderByCreatedAtAsc(d.getId());
        return !messages.isEmpty() && messages.get(messages.size() - 1).getSenderRole() == Role.INTERPRETER;
    }

    /**
     * Auto-submit after a successful payment (contract rule 4): same rules as {@link #submit}, but dreams that
     * are no longer the user's DRAFTs are skipped and it never throws for them. Joins the payment transaction.
     */
    @Transactional
    public List<UUID> autoSubmit(UUID userId, List<UUID> dreamIds, UUID orderId) {
        List<UUID> ids = distinct(dreamIds);
        if (ids.isEmpty()) {
            return List.of();
        }
        int balance = creditService.lockAndGetBalance(userId);
        List<Dream> drafts = order(dreamRepository.findAllByIdInAndUserIdForUpdate(ids, userId), ids).stream()
                .filter(d -> d.getStatus() == DreamStatus.DRAFT)
                .limit(Math.max(balance, 0))
                .toList();
        if (drafts.isEmpty()) {
            return List.of();
        }
        return doSubmit(userId, drafts, orderId);
    }

    private List<UUID> doSubmit(UUID userId, List<Dream> drafts, UUID orderId) {
        Instant now = clock.instant();
        int slaHours = currentSlaHours();
        List<UUID> submitted = new ArrayList<>();
        for (Dream d : drafts) {
            // the credit comes from the payment that holds it (its order id travels with the dream)
            CreditLedgerEntry entry = creditService.consumeForDream(userId, d.getId());
            d.setStatus(DreamStatus.IN_REVIEW);
            d.setSubmittedAt(now);
            d.setSlaHoursSnapshot(slaHours);
            d.setExpectedBy(now.plus(Duration.ofHours(slaHours)));
            d.setSlaPausedAt(null);
            d.setLedgerEntryId(entry.getId());
            dreamRepository.save(d);
            submitted.add(d.getId());
        }
        notifier.submitted(submitted);
        return submitted;
    }

    /** SLA snapshot at submit time (rule 7): busy ? busy_max_days × 24 : normal_hours. */
    int currentSlaHours() {
        if (settingsService.getBool(SettingKeys.WAIT_BUSY)) {
            return settingsService.getInt(SettingKeys.WAIT_BUSY_MAX_DAYS) * HOURS_PER_DAY;
        }
        return settingsService.getInt(SettingKeys.WAIT_NORMAL_HOURS);
    }


    // ================================================================== messages & testimonial

    /**
     * User reply: only from AWAITING_USER_REPLY → IN_PROGRESS when the interpreter had started, else IN_REVIEW; the
     * SLA deadline moves by the paused time.
     */
    @Transactional
    public DreamMessageDto reply(UUID userId, UUID dreamId, String body) {
        Dream d = ownedForUpdate(userId, dreamId);
        if (d.getStatus() != DreamStatus.AWAITING_USER_REPLY) {
            throw new ConflictException("No open question on this dream", "NO_OPEN_QUESTION");
        }
        Instant now = clock.instant();
        DreamMessage m = new DreamMessage();
        m.setDreamId(d.getId());
        m.setSenderRole(Role.USER);
        m.setBody(validBody(body));
        m.setCreatedAt(now);
        messageRepository.save(m);

        resumeSla(d, now);
        d.setStatus(d.getStartedAt() != null ? DreamStatus.IN_PROGRESS : DreamStatus.IN_REVIEW);
        dreamRepository.save(d);
        notifier.userReplied(d.getId());
        return DreamMessageDto.from(m);
    }

    @Transactional
    public void testimonial(UUID userId, UUID dreamId, DreamRequests.TestimonialRequest req) {
        Dream d = owned(userId, dreamId);
        if (d.getStatus() != DreamStatus.INTERPRETED) {
            throw new ConflictException("Testimonials are only possible after interpretation", "NOT_INTERPRETED");
        }
        if (testimonialRepository.existsByDreamId(dreamId)) {
            throw new ConflictException("This dream already has a testimonial", "TESTIMONIAL_EXISTS");
        }
        Testimonial t = new Testimonial();
        t.setDreamId(dreamId);
        t.setUserId(userId);
        t.setRating(req.rating());
        t.setComment(req.comment() == null ? "" : req.comment().trim());
        t.setApproved(false);
        t.setCreatedAt(clock.instant());
        testimonialRepository.save(t);

        // testimonial-received to the interpreter(s): it waits for approval
        String userName = userRepository.findById(userId).map(User::getName).orElse("");
        int rating = t.getRating();
        String comment = t.getComment();
        String ref = MailTemplates.TESTIMONIAL_RECEIVED + ":" + t.getId();
        eventMailer.toInterpreters(MailTemplates.TESTIMONIAL_RECEIVED, ref, locale -> {
            Map<String, Object> model = new LinkedHashMap<>();
            model.put("userName", userName);
            model.put("rating", rating);
            model.put("comment", comment);
            model.put(MailService.MODEL_LINK, FrontendPaths.ADMIN_TESTIMONIALS);
            return model;
        });
    }

    // ================================================================== helpers

    /** Moves expected_by forward by the time spent in AWAITING_USER_REPLY and clears the pause. */
    static void resumeSla(Dream d, Instant now) {
        if (d.getSlaPausedAt() != null && d.getExpectedBy() != null && now.isAfter(d.getSlaPausedAt())) {
            d.setExpectedBy(d.getExpectedBy().plus(Duration.between(d.getSlaPausedAt(), now)));
        }
        d.setSlaPausedAt(null);
    }

    /** Locks the row first (fresh state under concurrency), then checks ownership (404 otherwise). */
    private Dream ownedForUpdate(UUID userId, UUID dreamId) {
        return dreamRepository.findByIdForUpdate(dreamId)
                .filter(x -> x.getUserId().equals(userId))
                .orElseThrow(() -> NotFoundException.of("Dream", dreamId));
    }

    private Dream owned(UUID userId, UUID dreamId) {
        return dreamRepository.findByIdAndUserId(dreamId, userId).orElseThrow(() -> NotFoundException.of("Dream", dreamId));
    }

    private String validText(String raw) {
        String text = raw == null ? "" : raw.trim();
        int min = settingsService.getInt(SettingKeys.DREAMS_MIN_CHARS);
        int max = settingsService.getInt(SettingKeys.DREAMS_MAX_CHARS);
        if (text.length() < min) {
            throw fieldError("text", "min " + min);
        }
        if (text.length() > max) {
            throw fieldError("text", "max " + max);
        }
        return text;
    }

    /** Trimmed, non-empty message body within {@code dreams.max_chars}. */
    public String validBody(String raw) {
        String body = raw == null ? "" : raw.trim();
        if (body.isEmpty()) {
            throw fieldError("body", "required");
        }
        int max = settingsService.getInt(SettingKeys.DREAMS_MAX_CHARS);
        if (body.length() > max) {
            throw fieldError("body", "max " + max);
        }
        return body;
    }

    /** 400 with {@code errors: {field: [message]}} — same shape as Bean Validation failures. */
    static ApiException fieldError(String field, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed", null,
                "One or more fields are invalid")
                .with(Problems.PROP_ERRORS, Map.of(field, List.of(message)));
    }

    private static List<UUID> distinct(List<UUID> ids) {
        if (ids == null) {
            return List.of();
        }
        return new ArrayList<>(new LinkedHashSet<>(ids.stream().filter(java.util.Objects::nonNull).toList()));
    }

    /** Keeps the caller's order of ids. */
    private static List<Dream> order(List<Dream> dreams, List<UUID> ids) {
        Map<UUID, Dream> byId = dreams.stream().collect(Collectors.toMap(Dream::getId, Function.identity()));
        return ids.stream().map(byId::get).filter(java.util.Objects::nonNull).toList();
    }
}
