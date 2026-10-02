package com.saadat.dreams.service;

import com.saadat.common.util.Ages;

import com.saadat.common.api.PageResponse;
import com.saadat.common.api.Pages;
import com.saadat.common.audit.AuditService;
import com.saadat.common.domain.DreamStatus;
import com.saadat.common.domain.LedgerReason;
import com.saadat.common.domain.Role;
import com.saadat.common.error.ConflictException;
import com.saadat.common.error.NotFoundException;
import com.saadat.credits.service.CreditService;
import com.saadat.dreams.api.AdminDreamDetailDto;
import com.saadat.dreams.api.AdminDreamRow;
import com.saadat.dreams.api.AdminTestimonialRow;
import com.saadat.dreams.api.DreamDetailDto;
import com.saadat.dreams.api.DreamMessageDto;
import com.saadat.dreams.domain.Dream;
import com.saadat.dreams.domain.DreamMessage;
import com.saadat.dreams.domain.Interpretation;
import com.saadat.dreams.domain.Testimonial;
import com.saadat.dreams.repo.DreamMessageRepository;
import com.saadat.dreams.repo.DreamRepository;
import com.saadat.dreams.repo.InterpretationRepository;
import com.saadat.dreams.repo.TestimonialRepository;
import com.saadat.mail.EventMailer;
import com.saadat.mail.FrontendPaths;
import com.saadat.mail.MailService;
import com.saadat.mail.MailTemplates;
import com.saadat.payments.api.OrderDto;
import com.saadat.payments.domain.CreditLedgerEntry;
import com.saadat.payments.domain.Order;
import com.saadat.payments.repo.CreditLedgerRepository;
import com.saadat.payments.repo.OrderRepository;
import com.saadat.users.domain.User;
import com.saadat.users.repo.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The interpreter's side of the status machine: queue, questions (IN_REVIEW → AWAITING_USER_REPLY, SLA
 * paused), interpretation (→ INTERPRETED), cancel (→ CANCELLED + REFUND), testimonial moderation.
 * Every mutation is audited.
 */
@Service
@RequiredArgsConstructor
public class AdminDreamService {

    private final DreamRepository dreamRepository;
    private final DreamMessageRepository messageRepository;
    private final InterpretationRepository interpretationRepository;
    private final TestimonialRepository testimonialRepository;
    private final CreditLedgerRepository ledgerRepository;
    private final OrderRepository orderRepository;
    private final UserRepository userRepository;
    private final CreditService creditService;
    private final AuditService auditService;
    private final DreamMapper mapper;
    private final DreamNotifier notifier;
    private final DreamService dreamService;
    private final EventMailer eventMailer;
    private final Clock clock;

    // ================================================================== queue & detail

    /** Queue sorted by submitted_at asc; without a status filter every non-draft dream. */
    @Transactional(readOnly = true)
    public PageResponse<AdminDreamRow> queue(DreamStatus status, Integer page, Integer size) {
        Pageable pageable = Pages.of(page, size);
        if (status == DreamStatus.DRAFT) {
            return PageResponse.of(List.of(), pageable.getPageNumber(), pageable.getPageSize(), 0);
        }
        Page<Dream> dreams = status == null
                ? dreamRepository.findByStatusNotOrderBySubmittedAtAsc(DreamStatus.DRAFT, pageable)
                : dreamRepository.findByStatusOrderBySubmittedAtAsc(status, pageable);
        Map<UUID, User> users = usersById(dreams.getContent().stream().map(Dream::getUserId).distinct().toList());
        Instant now = clock.instant();
        Function<Dream, AdminDreamRow> toRow = d -> {
            User u = users.get(d.getUserId());
            return new AdminDreamRow(d.getId(), d.getUserId(), u == null ? "" : u.getName(), d.getGender(),
                    DreamMapper.excerpt(d.getText()), d.getStatus(), d.getSubmittedAt(), d.getExpectedBy(),
                    isOverdue(d, now), u == null || u.getCountryCode() == null ? "" : u.getCountryCode());
        };
        return PageResponse.from(dreams, toRow);
    }

    public static boolean isOverdue(Dream d, Instant now) {
        return d.getStatus() == DreamStatus.IN_REVIEW && d.getExpectedBy() != null && now.isAfter(d.getExpectedBy());
    }

    /** Detail with user{} and payment{} (ledger entry → order → payer); marks the user's messages read. */
    @Transactional
    public AdminDreamDetailDto detail(UUID dreamId) {
        Dream d = find(dreamId);
        messageRepository.markRead(d.getId(), Role.USER, clock.instant());
        DreamDetailDto detail = mapper.detail(d);
        User owner = userRepository.findById(d.getUserId())
                .orElseThrow(() -> new NotFoundException("Dream owner not found"));
        AdminDreamDetailDto.UserRef userRef = new AdminDreamDetailDto.UserRef(owner.getId(), owner.getName(), owner.getEmail(), owner.getCountryCode(), Ages.of(owner.getBirthDate()));
        return AdminDreamDetailDto.of(detail, userRef, payment(detail.credit()));
    }

    private AdminDreamDetailDto.PaymentRef payment(DreamDetailDto.CreditRef credit) {
        if (credit == null || credit.orderId() == null) {
            return null;
        }
        Order o = orderRepository.findById(credit.orderId()).orElse(null);
        if (o == null) {
            return null;
        }
        User payer = userRepository.findById(o.getUserId()).orElse(null);
        String ref = o.getProviderTxnId() != null ? o.getProviderTxnId()
                : (o.getProviderOrderId() != null ? o.getProviderOrderId() : "");
        return new AdminDreamDetailDto.PaymentRef(o.getId(), payer == null ? "" : payer.getName(),
                payer == null ? "" : payer.getEmail(), o.getPaidAt() != null ? o.getPaidAt() : o.getCreatedAt(),
                o.getPackageNameSnapshot(), o.getAmount(), o.getCurrency(), OrderDto.displayProvider(o).name(), ref,
                o.getCountryCode());
    }

    // ================================================================== transitions

    /** Interpreter question: only from IN_REVIEW → AWAITING_USER_REPLY; pauses the SLA clock. */
    @Transactional
    public DreamMessageDto ask(UUID actor, UUID dreamId, String body) {
        Dream d = lock(dreamId);
        if (d.getStatus() != DreamStatus.IN_REVIEW) {
            throw new ConflictException("Questions are only possible while the dream is in review", "NOT_IN_REVIEW");
        }
        Instant now = clock.instant();
        DreamMessage m = new DreamMessage();
        m.setDreamId(d.getId());
        m.setSenderRole(Role.INTERPRETER);
        m.setBody(dreamService.validBody(body));
        m.setCreatedAt(now);
        messageRepository.save(m);

        d.setStatus(DreamStatus.AWAITING_USER_REPLY);
        d.setSlaPausedAt(now);
        dreamRepository.save(d);
        auditService.record(actor, "DREAM_QUESTION", "dreams", dreamId.toString(),
                statusMap(DreamStatus.IN_REVIEW), statusMap(DreamStatus.AWAITING_USER_REPLY));
        notifier.interpreterQuestion(d.getId());
        return DreamMessageDto.from(m);
    }

    /** Interpretation: from IN_REVIEW or AWAITING_USER_REPLY → INTERPRETED. */
    @Transactional
    public void interpret(UUID actor, UUID dreamId, String text) {
        Dream d = lock(dreamId);
        if (d.getStatus() != DreamStatus.IN_REVIEW && d.getStatus() != DreamStatus.AWAITING_USER_REPLY) {
            throw new ConflictException("This dream cannot be interpreted in status " + d.getStatus(), "INVALID_STATUS");
        }
        if (interpretationRepository.existsByDreamId(dreamId)) {
            throw new ConflictException("This dream is already interpreted", "INVALID_STATUS");
        }
        DreamStatus before = d.getStatus();
        Instant now = clock.instant();
        Interpretation i = new Interpretation();
        i.setDreamId(dreamId);
        i.setInterpreterId(actor);
        i.setText(text.trim());
        i.setCreatedAt(now);
        interpretationRepository.save(i);

        d.setStatus(DreamStatus.INTERPRETED);
        d.setInterpretedAt(now);
        d.setSlaPausedAt(null);
        d.setAssigneeId(actor);
        dreamRepository.save(d);
        auditService.record(actor, "DREAM_INTERPRET", "dreams", dreamId.toString(), statusMap(before),
                statusMap(DreamStatus.INTERPRETED));
        notifier.interpretationReady(d.getId());
    }

    /** Cancel: from IN_REVIEW or AWAITING_USER_REPLY → CANCELLED and the consumed credit is refunded once. */
    @Transactional
    public void cancel(UUID actor, UUID dreamId, String reason) {
        // only the dream row is locked: a refund is a positive ledger entry and needs no user lock
        Dream d = lock(dreamId);
        if (d.getStatus() != DreamStatus.IN_REVIEW && d.getStatus() != DreamStatus.AWAITING_USER_REPLY) {
            throw new ConflictException("This dream cannot be cancelled in status " + d.getStatus(), "INVALID_STATUS");
        }
        DreamStatus before = d.getStatus();
        d.setStatus(DreamStatus.CANCELLED);
        d.setCancelledReason(reason == null ? null : reason.trim());
        d.setSlaPausedAt(null);
        dreamRepository.save(d);

        boolean refunded = false;
        if (d.getLedgerEntryId() != null && !ledgerRepository.existsByDreamIdAndReason(dreamId, LedgerReason.REFUND)) {
            UUID orderId = ledgerRepository.findById(d.getLedgerEntryId()).map(CreditLedgerEntry::getOrderId).orElse(null);
            creditService.add(d.getUserId(), 1, LedgerReason.REFUND, orderId, dreamId, reason, actor);
            refunded = true;
        }
        Map<String, Object> after = statusMap(DreamStatus.CANCELLED);
        after.put("reason", reason);
        auditService.record(actor, "DREAM_CANCEL", "dreams", dreamId.toString(), statusMap(before), after);
        mailCancelled(d, refunded);
    }

    /** {@code dream-cancelled} to the owner: reason, refunded credit, new balance (after commit). */
    private void mailCancelled(Dream d, boolean refunded) {
        User owner = userRepository.findById(d.getUserId()).filter(u -> !u.isDeleted()).orElse(null);
        if (owner == null) {
            return;
        }
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("excerpt", DreamMapper.excerpt(d.getText()));
        model.put("reason", d.getCancelledReason());
        model.put("refunded", refunded);
        model.put("balance", creditService.balance(owner.getId()));
        model.put(MailService.MODEL_LINK, DreamLinks.user(d.getId()));
        eventMailer.toUser(owner, MailTemplates.DREAM_CANCELLED, model, MailTemplates.DREAM_CANCELLED + ":" + d.getId());
    }

    // ================================================================== testimonials

    @Transactional(readOnly = true)
    public PageResponse<AdminTestimonialRow> testimonials(Boolean approved, Integer page, Integer size) {
        Page<Testimonial> rows = approved == null
                ? testimonialRepository.findAllByOrderByCreatedAtDesc(Pages.of(page, size))
                : testimonialRepository.findByApproved(approved,
                        Pages.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
        Map<UUID, User> users = usersById(rows.getContent().stream().map(Testimonial::getUserId).distinct().toList());
        Function<Testimonial, AdminTestimonialRow> toRow = t -> new AdminTestimonialRow(t.getId(),
                users.containsKey(t.getUserId()) ? users.get(t.getUserId()).getName() : "", t.getDreamId(),
                t.getRating(), t.getComment(), t.isApproved(), t.getCreatedAt());
        return PageResponse.from(rows, toRow);
    }

    /** {@code id} is the testimonial id; a dream id is accepted too (the reference mock keys rows by dream). */
    @Transactional
    public void setTestimonialApproved(UUID actor, UUID id, boolean approved) {
        Testimonial t = testimonialRepository.findById(id)
                .or(() -> testimonialRepository.findByDreamId(id))
                .orElseThrow(() -> NotFoundException.of("Testimonial", id));
        boolean before = t.isApproved();
        t.setApproved(approved);
        testimonialRepository.save(t);
        auditService.record(actor, "TESTIMONIAL_APPROVAL", "testimonials", t.getId().toString(),
                Map.of("approved", before), Map.of("approved", approved));
        if (approved && !before) {
            mailTestimonialApproved(t);
        }
    }

    /** {@code testimonial-approved} to the author, once per testimonial (after commit). */
    private void mailTestimonialApproved(Testimonial t) {
        User author = userRepository.findById(t.getUserId()).filter(u -> !u.isDeleted()).orElse(null);
        if (author == null) {
            return;
        }
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("rating", t.getRating());
        model.put("comment", t.getComment());
        model.put(MailService.MODEL_LINK, FrontendPaths.HOME);
        eventMailer.toUserOnce(author, MailTemplates.TESTIMONIAL_APPROVED, model,
                MailTemplates.TESTIMONIAL_APPROVED + ":" + t.getId());
    }

    // ================================================================== helpers

    private Dream find(UUID id) {
        return dreamRepository.findById(id).orElseThrow(() -> NotFoundException.of("Dream", id));
    }

    private Dream lock(UUID id) {
        Dream d = dreamRepository.findByIdForUpdate(id).orElseThrow(() -> NotFoundException.of("Dream", id));
        if (d.getStatus() == DreamStatus.DRAFT) {
            // drafts are private to their owner
            throw NotFoundException.of("Dream", id);
        }
        return d;
    }

    private Map<UUID, User> usersById(List<UUID> ids) {
        return userRepository.findAllById(ids).stream().collect(Collectors.toMap(User::getId, Function.identity()));
    }

    private static Map<String, Object> statusMap(DreamStatus status) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", status);
        return m;
    }
}
