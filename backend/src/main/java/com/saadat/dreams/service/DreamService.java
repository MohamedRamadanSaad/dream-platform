package com.saadat.dreams.service;

import com.saadat.common.api.PageResponse;
import com.saadat.common.api.Pages;
import com.saadat.common.domain.DreamStatus;
import com.saadat.common.domain.LedgerReason;
import com.saadat.common.domain.OrderStatus;
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
import com.saadat.payments.domain.CreditLedgerEntry;
import com.saadat.payments.domain.Order;
import com.saadat.payments.repo.OrderRepository;
import com.saadat.settings.SettingKeys;
import com.saadat.settings.SettingsService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
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
    private final OrderRepository orderRepository;
    private final CreditService creditService;
    private final SettingsService settingsService;
    private final DreamMapper mapper;
    private final DreamNotifier notifier;
    private final Clock clock;

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

    @Transactional
    public DreamDetailDto updateDraft(UUID userId, UUID dreamId, DreamRequests.DraftRequest req) {
        Dream d = ownedForUpdate(userId, dreamId);
        if (d.getStatus() != DreamStatus.DRAFT) {
            throw new ConflictException("Only drafts can be edited", "NOT_DRAFT");
        }
        d.setText(validText(req.text()));
        d.setGender(req.gender());
        dreamRepository.save(d);
        return mapper.detail(d);
    }

    @Transactional
    public void deleteDraft(UUID userId, UUID dreamId) {
        Dream d = ownedForUpdate(userId, dreamId);
        if (d.getStatus() != DreamStatus.DRAFT) {
            throw new ConflictException("Only drafts can be deleted", "NOT_DRAFT");
        }
        dreamRepository.delete(d);
    }

    // ================================================================== reads

    @Transactional(readOnly = true)
    public PageResponse<DreamSummaryDto> list(UUID userId, DreamStatus status, Integer page, Integer size) {
        Pageable pageable = Pages.of(page, size);
        Page<Dream> dreams = status == null
                ? dreamRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable)
                : dreamRepository.findByUserIdAndStatusOrderByCreatedAtDesc(userId, status, pageable);
        Function<Dream, DreamSummaryDto> toDto = mapper::summary;
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
     * atomically (else 402 INSUFFICIENT_CREDITS, nothing submitted).
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
        if (dreams.size() != ids.size() || dreams.stream().anyMatch(d -> d.getStatus() != DreamStatus.DRAFT)) {
            throw new ConflictException("Some dreams are not your drafts", "NOT_DRAFT");
        }
        if (balance < dreams.size()) {
            throw new PaymentRequiredException(dreams.size() - balance);
        }
        List<UUID> submitted = doSubmit(userId, order(dreams, ids), orderId);
        // same (read-write) transaction and lock; the aggregate query auto-flushes the new ledger rows
        return new DreamRequests.SubmitResponse(submitted, creditService.lockAndGetBalance(userId));
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
        UUID payingOrder = orderId != null ? orderId : latestPaidOrderId(userId);
        List<UUID> submitted = new ArrayList<>();
        for (Dream d : drafts) {
            CreditLedgerEntry entry = creditService.consume(userId, 1, LedgerReason.SUBMIT, d.getId(), payingOrder);
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

    /** Links a plain submit to the user's most recent paid order (for the interpreter's payment view). */
    private UUID latestPaidOrderId(UUID userId) {
        return orderRepository.findByUserIdAndStatus(userId, OrderStatus.SUCCESS).stream()
                .filter(o -> o.getPaidAt() != null)
                .max(Comparator.comparing(Order::getPaidAt))
                .map(Order::getId)
                .orElse(null);
    }

    // ================================================================== messages & testimonial

    /** User reply: only from AWAITING_USER_REPLY → IN_REVIEW; the SLA deadline moves by the paused time. */
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
        d.setStatus(DreamStatus.IN_REVIEW);
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
