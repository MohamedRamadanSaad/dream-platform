package com.saadat.payments.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.saadat.common.domain.DreamStatus;
import com.saadat.common.domain.OrderStatus;
import com.saadat.common.error.ApiException;
import com.saadat.common.error.ConflictException;
import com.saadat.common.error.NotFoundException;
import com.saadat.dreams.api.DreamPaymentLink;
import com.saadat.dreams.domain.Dream;
import com.saadat.dreams.repo.DreamRepository;
import com.saadat.payments.domain.Order;
import com.saadat.payments.repo.OrderRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Drafts attached to a payment (docs/UX_PAYMENTS_REVIEW_CONTRACT.md §2), read from {@code orders.checkout_intent}
 * ({@code {"dreamIds":[...]}}). A user has few orders, so the intents are read in Java (this also tolerates the
 * double-encoded JSON string some old rows hold).
 *
 * <ul>
 *   <li>Shown as attached (tab "Attached to a payment"): a DRAFT whose id is in the intent of one of the user's orders
 *       that is PENDING_REVIEW, or FAILED with {@code TRANSFER_REJECTED} / {@code APPROVAL_REVOKED} (the latest such
 *       order wins), and every AWAITING_PAYMENT dream (held by an order whose approval was taken back, V40).</li>
 *   <li>Held (blocks deleting, submitting or attaching elsewhere): only INITIATED orders not expired yet and
 *       PENDING_REVIEW orders. A rejected order never holds a dream: the dreamer may delete it, submit it with credits
 *       or attach it to another order (which removes it from the rejected order's intent), so a dream is never
 *       stuck.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DreamAttachments {

    static final String INTENT_DREAM_IDS = "dreamIds";
    public static final String CODE_ALREADY_ATTACHED = "DREAM_ALREADY_ATTACHED";
    public static final String CODE_TOO_MANY_DREAMS = "TOO_MANY_DREAMS";
    public static final String CODE_NOT_RELEASABLE = "NOT_RELEASABLE";

    private static final List<OrderStatus> CANDIDATE_STATUSES = List.of(OrderStatus.INITIATED,
            OrderStatus.PENDING_REVIEW, OrderStatus.FAILED);

    private final OrderRepository orderRepository;
    private final DreamRepository dreamRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    // ================================================================== intent JSON

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

    /** {@code {"dreamIds":[...]}}, or null when there is none. */
    public String intentJson(Collection<UUID> dreamIds) {
        List<UUID> ids = dreamIds == null ? List.of()
                : new ArrayList<>(new LinkedHashSet<>(dreamIds.stream().filter(Objects::nonNull).toList()));
        if (ids.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(Map.of(INTENT_DREAM_IDS, ids));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize checkout intent", e);
        }
    }

    // ================================================================== order states

    /** The order shows its dreams as attached: a payment under review, or rejected / revoked and resendable. */
    public static boolean holdsAttachedDreams(Order o) {
        return o.getStatus() == OrderStatus.PENDING_REVIEW || isRejected(o);
    }

    /**
     * FAILED and the buyer may send the transfer details again: rejected by the interpreter, or its approval taken
     * back (V40). Such an order never traps its drafts.
     */
    public static boolean isRejected(Order o) {
        return o.getStatus() == OrderStatus.FAILED && (InstapayService.REASON_REJECTED.equals(o.getFailureReason())
                || InstapayService.REASON_REVOKED.equals(o.getFailureReason()));
    }

    /** What a dream waiting on {@code o} shows as {@code payment.status}. */
    public static String linkStatus(Order o) {
        if (o.getStatus() == OrderStatus.PENDING_REVIEW) {
            return DreamPaymentLink.PENDING_REVIEW;
        }
        if (o.getStatus() == OrderStatus.FAILED && InstapayService.REASON_REVOKED.equals(o.getFailureReason())) {
            return DreamPaymentLink.REVOKED;
        }
        return DreamPaymentLink.REJECTED;
    }

    static DreamPaymentLink link(Order o) {
        return new DreamPaymentLink(o.getId(), linkStatus(o), o.getTransferSubmittedAt(), o.getReviewNote());
    }

    /** The order blocks its dreams: INITIATED and not expired, or PENDING_REVIEW. */
    boolean isLive(Order o, Instant now) {
        if (o.getStatus() == OrderStatus.PENDING_REVIEW) {
            return true;
        }
        return o.getStatus() == OrderStatus.INITIATED && (o.getExpiresAt() == null || o.getExpiresAt().isAfter(now));
    }

    // ================================================================== reads

    /**
     * The user's current DRAFT dreams shown as attached to a payment (PENDING_REVIEW or rejected), with the payment
     * they wait on; the latest order wins.
     */
    @Transactional(readOnly = true)
    public Map<UUID, DreamPaymentLink> attachedDrafts(UUID userId) {
        Map<UUID, DreamPaymentLink> links = new LinkedHashMap<>();
        for (Order o : candidates(userId)) {
            if (!holdsAttachedDreams(o)) {
                continue;
            }
            DreamPaymentLink link = link(o);
            for (UUID id : readIntent(o.getCheckoutIntent())) {
                links.putIfAbsent(id, link);
            }
        }
        if (links.isEmpty()) {
            return links;
        }
        // only the user's dreams that are still drafts (soft-deleted rows are never loaded)
        Set<UUID> drafts = new HashSet<>();
        for (Dream d : dreamRepository.findAllById(links.keySet())) {
            if (d.getStatus() == DreamStatus.DRAFT && userId.equals(d.getUserId())) {
                drafts.add(d.getId());
            }
        }
        links.keySet().retainAll(drafts);
        return links;
    }

    /**
     * The user's AWAITING_PAYMENT dreams (V40) with the payment each one waits on: its order revoked (REVOKED), sent
     * again (PENDING_REVIEW) or rejected after that (REJECTED).
     */
    @Transactional(readOnly = true)
    public Map<UUID, DreamPaymentLink> heldDreams(UUID userId) {
        return heldLinks(dreamRepository.findByUserIdAndStatusOrderByCreatedAtDesc(userId, DreamStatus.AWAITING_PAYMENT));
    }

    /** Links of the given held dreams (others are skipped). */
    @Transactional(readOnly = true)
    public Map<UUID, DreamPaymentLink> heldLinks(Collection<Dream> dreams) {
        Map<UUID, DreamPaymentLink> links = new LinkedHashMap<>();
        Map<UUID, Order> orders = new LinkedHashMap<>();
        for (Dream d : dreams) {
            if (d.getStatus() != DreamStatus.AWAITING_PAYMENT || d.getHeldOrderId() == null) {
                continue;
            }
            Order o = orders.computeIfAbsent(d.getHeldOrderId(), id -> orderRepository.findById(id).orElse(null));
            if (o != null) {
                links.put(d.getId(), link(o));
            }
        }
        return links;
    }

    /**
     * The payment {@code dream} is shown as waiting on, or null: a draft attached to a payment, or a dream held by an
     * order whose approval was taken back (V40).
     */
    @Transactional(readOnly = true)
    public DreamPaymentLink linkOf(Dream dream) {
        if (dream == null) {
            return null;
        }
        if (dream.getStatus() == DreamStatus.AWAITING_PAYMENT) {
            return heldLinks(List.of(dream)).get(dream.getId());
        }
        if (dream.getStatus() != DreamStatus.DRAFT) {
            return null;
        }
        return attachedDrafts(dream.getUserId()).get(dream.getId());
    }

    /**
     * The ids among {@code dreamIds} held by a live order of the user other than {@code exceptOrderId}, with the order
     * that holds each one.
     */
    @Transactional(readOnly = true)
    public Map<UUID, UUID> heldElsewhere(UUID userId, Collection<UUID> dreamIds, UUID exceptOrderId) {
        Map<UUID, UUID> held = new LinkedHashMap<>();
        if (dreamIds == null || dreamIds.isEmpty()) {
            return held;
        }
        Instant now = clock.instant();
        for (Order o : candidates(userId)) {
            if (Objects.equals(o.getId(), exceptOrderId) || !isLive(o, now)) {
                continue;
            }
            List<UUID> intent = readIntent(o.getCheckoutIntent());
            for (UUID id : dreamIds) {
                if (id != null && intent.contains(id)) {
                    held.putIfAbsent(id, o.getId());
                }
            }
        }
        return held;
    }

    // ================================================================== rules

    /**
     * 409 {@code code} when one of {@code dreamIds} is held by another live order of the user (INITIATED not expired,
     * or PENDING_REVIEW); the problem carries {@code orderId} (that order) and {@code dreamId}, so the client can offer
     * to release it. {@code exceptOrderId} is the order being changed (its own dreams are fine), or null.
     */
    @Transactional(readOnly = true)
    public void requireNotHeld(UUID userId, Collection<UUID> dreamIds, UUID exceptOrderId, String code) {
        Map<UUID, UUID> held = heldElsewhere(userId, dreamIds, exceptOrderId);
        if (!held.isEmpty()) {
            Map.Entry<UUID, UUID> first = held.entrySet().iterator().next();
            throw new ConflictException("This dream is attached to another payment", code)
                    .with("orderId", first.getValue())
                    .with("dreamId", first.getKey());
        }
    }

    /** {@link #requireNotHeld} with 409 {@code DREAM_ALREADY_ATTACHED} (checkout, InstaPay submit). */
    @Transactional(readOnly = true)
    public void requireFree(UUID userId, Collection<UUID> dreamIds, UUID exceptOrderId) {
        requireNotHeld(userId, dreamIds, exceptOrderId, CODE_ALREADY_ATTACHED);
    }

    /** 400 {@code TOO_MANY_DREAMS} when an order would carry more dreams than the credits it buys. */
    public static void requireWithinCredits(int dreams, int credits) {
        if (dreams > credits) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "bad-request", CODE_TOO_MANY_DREAMS,
                    "This payment covers " + credits + " dream(s) only")
                    .with("credits", credits)
                    .with("dreams", dreams);
        }
    }

    /**
     * The dreams leave every rejected order of the user other than {@code exceptOrderId} (they are submitted, deleted
     * or attached to another order). Rows are locked; joins the caller's transaction.
     */
    @Transactional
    public void detachFromRejected(UUID userId, Collection<UUID> dreamIds, UUID exceptOrderId) {
        if (dreamIds == null || dreamIds.isEmpty()) {
            return;
        }
        Set<UUID> leaving = new HashSet<>(dreamIds);
        for (Order o : candidates(userId)) {
            if (Objects.equals(o.getId(), exceptOrderId) || !isRejected(o)) {
                continue;
            }
            List<UUID> intent = readIntent(o.getCheckoutIntent());
            if (intent.stream().noneMatch(leaving::contains)) {
                continue;
            }
            Order locked = orderRepository.findByIdForUpdate(o.getId()).orElse(null);
            if (locked == null || !isRejected(locked)) {
                continue;
            }
            List<UUID> kept = new ArrayList<>(readIntent(locked.getCheckoutIntent()));
            kept.removeIf(leaving::contains);
            locked.setCheckoutIntent(intentJson(kept));
            orderRepository.save(locked);
        }
    }

    /**
     * {@code POST /me/orders/{id}/release-dreams}: the caller's order (INITIATED, PENDING_REVIEW, or rejected) lets go
     * of {@code dreamIds} (all when null/empty); they return to the drafts. 404 for someone else's order, 409
     * {@code NOT_RELEASABLE} in any other state. Returns the ids released.
     */
    @Transactional
    public List<UUID> release(UUID userId, UUID orderId, Collection<UUID> dreamIds) {
        Order order = orderRepository.findByIdForUpdate(orderId)
                .filter(o -> o.getUserId().equals(userId))
                .orElseThrow(() -> NotFoundException.of("Order", orderId));
        boolean releasable = order.getStatus() == OrderStatus.INITIATED
                || order.getStatus() == OrderStatus.PENDING_REVIEW || isRejected(order);
        if (!releasable) {
            throw new ConflictException("The dreams of this order can no longer be released", CODE_NOT_RELEASABLE);
        }
        List<UUID> intent = new ArrayList<>(readIntent(order.getCheckoutIntent()));
        List<UUID> released;
        if (dreamIds == null || dreamIds.isEmpty()) {
            released = List.copyOf(intent);
            intent.clear();
        } else {
            Set<UUID> wanted = new HashSet<>(dreamIds);
            released = intent.stream().filter(wanted::contains).toList();
            intent.removeIf(wanted::contains);
        }
        order.setCheckoutIntent(intentJson(intent));
        orderRepository.save(order);
        return released;
    }

    private List<Order> candidates(UUID userId) {
        return orderRepository.findByUserIdAndStatusInOrderByCreatedAtDesc(userId, CANDIDATE_STATUSES);
    }
}
