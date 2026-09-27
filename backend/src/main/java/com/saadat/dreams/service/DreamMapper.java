package com.saadat.dreams.service;

import com.saadat.common.domain.Role;
import com.saadat.dreams.api.DreamDetailDto;
import com.saadat.dreams.api.DreamMessageDto;
import com.saadat.dreams.api.DreamSummaryDto;
import com.saadat.dreams.domain.Dream;
import com.saadat.dreams.repo.DreamMessageRepository;
import com.saadat.dreams.repo.InterpretationRepository;
import com.saadat.dreams.repo.TestimonialRepository;
import com.saadat.payments.repo.CreditLedgerRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Entity → types.ts DTO mapping for dreams. Callers must be transactional (it only reads). */
@Component
@RequiredArgsConstructor
public class DreamMapper {

    /** Excerpt length of the reference mock ({@code excerpt()} in mocks/data.ts). */
    public static final int EXCERPT_LENGTH = 110;
    private static final String ELLIPSIS = "…";

    private final DreamMessageRepository messageRepository;
    private final InterpretationRepository interpretationRepository;
    private final TestimonialRepository testimonialRepository;
    private final CreditLedgerRepository ledgerRepository;

    public static String excerpt(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > EXCERPT_LENGTH ? text.substring(0, EXCERPT_LENGTH) + ELLIPSIS : text;
    }

    /** Summary for the dream's owner: unread = interpreter messages not yet read. */
    public DreamSummaryDto summary(Dream d) {
        long unread = messageRepository.countByDreamIdAndSenderRoleAndReadAtIsNull(d.getId(), Role.INTERPRETER);
        return new DreamSummaryDto(d.getId(), excerpt(d.getText()), d.getStatus(), d.getCreatedAt(), d.getSubmittedAt(),
                d.getInterpretedAt(), d.getExpectedBy(), unread);
    }

    public DreamDetailDto detail(Dream d) {
        List<DreamMessageDto> messages = messageRepository.findByDreamIdOrderByCreatedAtAsc(d.getId()).stream()
                .map(DreamMessageDto::from).toList();
        long unread = messages.stream()
                .filter(m -> m.senderRole() == Role.INTERPRETER && m.readAt() == null).count();
        DreamDetailDto.InterpretationView interpretation = interpretationRepository.findByDreamId(d.getId())
                .map(i -> new DreamDetailDto.InterpretationView(i.getText(),
                        d.getInterpretedAt() != null ? d.getInterpretedAt() : i.getCreatedAt()))
                .orElse(null);
        DreamDetailDto.TestimonialView testimonial = testimonialRepository.findByDreamId(d.getId())
                .map(t -> new DreamDetailDto.TestimonialView(t.getRating(), t.getComment(), t.isApproved()))
                .orElse(null);
        DreamDetailDto.CreditRef credit = d.getLedgerEntryId() == null ? null
                : ledgerRepository.findById(d.getLedgerEntryId())
                        .map(e -> new DreamDetailDto.CreditRef(e.getId(), e.getOrderId()))
                        .orElse(new DreamDetailDto.CreditRef(d.getLedgerEntryId(), null));
        return new DreamDetailDto(d.getId(), excerpt(d.getText()), d.getStatus(), d.getCreatedAt(), d.getSubmittedAt(),
                d.getInterpretedAt(), d.getExpectedBy(), unread, d.getText(), d.getGender(), interpretation, messages,
                testimonial, credit);
    }
}
