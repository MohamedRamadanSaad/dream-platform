package com.saadat.payments.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.saadat.common.domain.DreamStatus;
import com.saadat.common.domain.OrderStatus;
import com.saadat.common.error.ConflictException;
import com.saadat.dreams.api.DreamPaymentLink;
import com.saadat.dreams.domain.Dream;
import com.saadat.dreams.repo.DreamRepository;
import com.saadat.payments.domain.Order;
import com.saadat.payments.repo.OrderRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Drafts attached to a payment (docs/UX_PAYMENTS_REVIEW_CONTRACT.md §2), read from {@code orders.checkout_intent}
 * ({@code {"dreamIds":[...]}}). A user has few orders, so the intents are read in Java (this also tolerates the
 * double-encoded JSON string some old rows hold).
 *
 * <ul>
 *   <li>Attached: a DRAFT whose id is in the intent of one of the user's orders that is PENDING_REVIEW, or FAILED
 *       with {@code TRANSFER_REJECTED} (the latest such order wins).</li>
 *   <li>Live (blocks attaching the same dream elsewhere): those plus INITIATED orders not expired yet.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DreamAttachments {

    static final String INTENT_DREAM_IDS = "dreamIds";
    public static final String CODE_ALREADY_ATTACHED = "DREAM_ALREADY_ATTACHED";

    private static final List<OrderStatus> CANDIDATE_STATUSES = List.of(OrderStatus.INITIATED,
            OrderStatus.PENDING_REVIEW, OrderStatus.FAILED);

    private final OrderRepository orderRepository;
    private final DreamRepository dreamRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /** The dream ids of a checkout intent (never null; malformed ids are skipped). */
    public List<UUID> readIntent(String json) {
        return parseIntent(objectMapper, json);
    }

    static List<UUID> parseIntent(ObjectMapper objectMapper, String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            // tolerate a double-encoded JSON string
            if (node.isTextual()) {
                node = objectMapper.readTree(node.asText());
            }
            List<UUID> ids = new ArrayList<>();
            for (JsonNode n : node.path(INTENT_DREAM_IDS)) {
                try {
                    UUID id = UUID.fromString(n.asText());
                    if (!ids.contains(id)) {
                        ids.add(id);
                    }
                } catch (IllegalArgumentException ignored) {
                    // skip malformed ids
                }
            }
            return ids;
        } catch (JsonProcessingException e) {
            log.warn("Unreadable checkout_intent: {}", e.getMessage());
            return List.of();
        }
    }

    /** The order attaches its dreams to a payment under review (or rejected and resendable). */
    public static boolean holdsAttachedDreams(Order o) {
        return o.getStatus() == OrderStatus.PENDING_REVIEW || isRejected(o);
    }

    static boolean isRejected(Order o) {
        return o.getStatus() == OrderStatus.FAILED && InstapayService.REASON_REJECTED.equals(o.getFailureReason());
    }

    /** Live: attached, or INITIATED and not expired. */
    boolean isLive(Order o, Instant now) {
        if (holdsAttachedDreams(o)) {
            return true;
        }
        return o.getStatus() == OrderStatus.INITIATED && (o.getExpiresAt() == null || o.getExpiresAt().isAfter(now));
    }

    /**
     * The user's current DRAFT dreams attached to a payment, with the payment they wait on (insertion order: the
     * latest order first).
     */
    @Transactional(readOnly = true)
    public Map<UUID, DreamPaymentLink> attachedDrafts(UUID userId) {
        Map<UUID, DreamPaymentLink> links = new LinkedHashMap<>();
        for (Order o : orderRepository.findByUserIdAndStatusInOrderByCreatedAtDesc(userId, CANDIDATE_STATUSES)) {
            if (!holdsAttachedDreams(o)) {
                continue;
            }
            DreamPaymentLink link = new DreamPaymentLink(o.getId(),
                    o.getStatus() == OrderStatus.PENDING_REVIEW ? DreamPaymentLink.PENDING_REVIEW : DreamPaymentLink.REJECTED,
                    o.getTransferSubmittedAt(), o.getReviewNote());
            for (UUID id : readIntent(o.getCheckoutIntent())) {
                links.putIfAbsent(id, link);
            }
        }
        if (links.isEmpty()) {
            return links;
        }
        // only the user's dreams that are still drafts (soft-deleted rows are never loaded)
        Set<UUID> drafts = new java.util.HashSet<>();
        for (Dream d : dreamRepository.findAllById(links.keySet())) {
            if (d.getStatus() == DreamStatus.DRAFT && userId.equals(d.getUserId())) {
                drafts.add(d.getId());
            }
        }
        links.keySet().retainAll(drafts);
        return links;
    }

    /** The payment {@code dream} waits on, or null (only drafts can be attached). */
    @Transactional(readOnly = true)
    public DreamPaymentLink linkOf(Dream dream) {
        if (dream == null || dream.getStatus() != DreamStatus.DRAFT) {
            return null;
        }
        return attachedDrafts(dream.getUserId()).get(dream.getId());
    }

    /**
     * 409 {@code DREAM_ALREADY_ATTACHED} when one of {@code dreamIds} is in the intent of another live order of the
     * user (INITIATED not expired, PENDING_REVIEW, or rejected-and-resendable). {@code exceptOrderId} is the order
     * being changed (its own dreams are fine), or null for a new checkout.
     */
    @Transactional(readOnly = true)
    public void requireFree(UUID userId, Collection<UUID> dreamIds, UUID exceptOrderId) {
        if (dreamIds == null || dreamIds.isEmpty()) {
            return;
        }
        Instant now = clock.instant();
        for (Order o : orderRepository.findByUserIdAndStatusInOrderByCreatedAtDesc(userId, CANDIDATE_STATUSES)) {
            if (Objects.equals(o.getId(), exceptOrderId) || !isLive(o, now)) {
                continue;
            }
            List<UUID> held = readIntent(o.getCheckoutIntent());
            for (UUID id : dreamIds) {
                if (id != null && held.contains(id)) {
                    throw new ConflictException("This dream is already attached to another payment",
                            CODE_ALREADY_ATTACHED);
                }
            }
        }
    }
}
