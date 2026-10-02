package com.saadat.admin.analytics;

import com.saadat.common.util.Ages;

import com.saadat.admin.analytics.AnalyticsDtos.AdminUser360Dto;
import com.saadat.admin.analytics.AnalyticsDtos.AdminUserRowDto;
import com.saadat.admin.analytics.AnalyticsDtos.UserTestimonialDto;
import com.saadat.common.audit.AuditService;
import com.saadat.common.domain.AuthProvider;
import com.saadat.common.domain.LedgerReason;
import com.saadat.common.error.NotFoundException;
import com.saadat.credits.service.CreditService;
import com.saadat.dreams.api.DreamSummaryDto;
import com.saadat.dreams.repo.DreamRepository;
import com.saadat.dreams.repo.TestimonialRepository;
import com.saadat.dreams.service.DreamMapper;
import com.saadat.mail.EventMailer;
import com.saadat.mail.FrontendPaths;
import com.saadat.mail.MailService;
import com.saadat.mail.MailTemplates;
import com.saadat.payments.domain.CreditLedgerEntry;
import com.saadat.payments.service.OrderQueryService;
import com.saadat.users.domain.AuthIdentity;
import com.saadat.users.domain.User;
import com.saadat.users.domain.UserNote;
import com.saadat.users.repo.AuthIdentityRepository;
import com.saadat.users.repo.UserNoteRepository;
import com.saadat.users.repo.UserRepository;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** GET /admin/users/{id} (360° view), notes/tags, manual credit adjustments (audited). */
@Service
@RequiredArgsConstructor
public class AdminUserService {

    private final UserRepository userRepository;
    private final AuthIdentityRepository identityRepository;
    private final UserNoteRepository noteRepository;
    private final DreamRepository dreamRepository;
    private final TestimonialRepository testimonialRepository;
    private final AnalyticsService analyticsService;
    private final CreditService creditService;
    private final OrderQueryService orderQueryService;
    private final DreamMapper dreamMapper;
    private final AuditService auditService;
    private final EventMailer eventMailer;
    private final Clock clock;

    @Transactional(readOnly = true)
    public AdminUser360Dto user360(UUID userId) {
        User u = userRepository.findById(userId).orElseThrow(() -> NotFoundException.of("User", userId));
        AdminUserRowDto row = analyticsService.userRow(userId);
        List<AuthProvider> providers = identityRepository.findByUserId(userId).stream()
                .map(AuthIdentity::getProvider).distinct().toList();
        List<DreamSummaryDto> dreams = dreamRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(dreamMapper::summary).toList();
        List<UserTestimonialDto> testimonials = testimonialRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(t -> new UserTestimonialDto(t.getDreamId(), t.getRating(), t.getComment(), t.isApproved()))
                .toList();
        UserNote note = noteRepository.findById(userId).orElse(null);
        return new AdminUser360Dto(
                u.getId(), u.getName(), u.getEmail(), u.getCountryCode(),
                row == null ? 0 : row.visits(),
                row == null ? 0 : row.dreams(),
                row == null ? 0 : row.drafts(),
                row == null ? 0 : row.totalPaidBase(),
                row == null ? null : row.avgRating(),
                row == null ? u.getCreatedAt() : row.lastSeenAt(),
                u.getGender(), u.getBirthDate(), Ages.of(u.getBirthDate()), u.getLocale(), providers, u.getCreatedAt(),
                creditService.summary(userId),
                orderQueryService.ofUser(userId),
                dreams,
                testimonials,
                note == null ? "" : note.getNotes(),
                note == null ? List.of() : List.copyOf(note.getTags()));
    }

    @Transactional
    public void saveNotes(UUID actor, UUID userId, String notes, List<String> tags) {
        if (!userRepository.existsById(userId)) {
            throw NotFoundException.of("User", userId);
        }
        UserNote note = noteRepository.findById(userId).orElseGet(() -> new UserNote(userId));
        Map<String, Object> before = notesMap(note.getNotes(), note.getTags());
        List<String> cleanTags = tags == null ? List.of() : tags.stream()
                .filter(Objects::nonNull).map(String::trim).filter(t -> !t.isEmpty()).distinct().toList();
        note.setNotes(notes == null ? "" : notes);
        note.setTags(new ArrayList<>(cleanTags));
        note.setUpdatedAt(clock.instant());
        noteRepository.save(note);
        auditService.record(actor, "USER_NOTES_UPDATE", "user_notes", userId.toString(), before,
                notesMap(note.getNotes(), note.getTags()));
    }

    @Transactional
    public void adjustCredits(UUID actor, UUID userId, int delta, String reason) {
        if (!userRepository.existsById(userId)) {
            throw NotFoundException.of("User", userId);
        }
        int before = creditService.balance(userId);
        CreditLedgerEntry entry = creditService.add(userId, delta, LedgerReason.MANUAL, null, reason, actor);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("balance", before + delta);
        after.put("delta", delta);
        after.put("reason", reason);
        after.put("ledgerEntryId", entry.getId());
        auditService.record(actor, "CREDITS_ADJUST", "credit_ledger", userId.toString(), Map.of("balance", before),
                after);
        mailCreditsAdjusted(userId, delta, reason, before + delta, entry.getId());
    }

    /** {@code credits-adjusted} to the user: signed delta, reason, new balance (after commit). */
    private void mailCreditsAdjusted(UUID userId, int delta, String reason, int balance, UUID entryId) {
        User user = userRepository.findById(userId).filter(u -> !u.isDeleted()).orElse(null);
        if (user == null) {
            return;
        }
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("delta", (delta > 0 ? "+" : "") + delta);
        model.put("reason", reason);
        model.put("balance", balance);
        model.put(MailService.MODEL_LINK, FrontendPaths.ME);
        eventMailer.toUser(user, MailTemplates.CREDITS_ADJUSTED, model, MailTemplates.CREDITS_ADJUSTED + ":" + entryId);
    }

    private static Map<String, Object> notesMap(String notes, List<String> tags) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("notes", notes);
        m.put("tags", tags == null ? List.of() : List.copyOf(tags));
        return m;
    }
}
