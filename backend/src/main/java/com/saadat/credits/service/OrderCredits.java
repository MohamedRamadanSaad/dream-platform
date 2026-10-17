package com.saadat.credits.service;

import com.saadat.common.domain.LedgerReason;
import com.saadat.payments.domain.CreditLedgerEntry;
import com.saadat.payments.repo.CreditLedgerRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The counter of each paid order: how many dreams its package gave, which dreams used them (in order) and how many
 * are left. Read from the ledger (the source of truth), never stored: a dream's SUBMIT row carries the order of the
 * credit it used, a cancelled (or held, V40) dream's REFUND row gives the credit back to the same order.
 */
@Service
@RequiredArgsConstructor
public class OrderCredits {

    private final CreditLedgerRepository ledgerRepository;
    private final Clock clock;

    /**
     * One paid order of a user.
     *
     * @param credits   dreams the package gave (bonus included)
     * @param remaining dreams still available from this order (0 once expired)
     * @param expired   the order's credits passed their expiry date
     * @param dreamIds  dreams that used this order, oldest first (cancelled and refunded ones removed)
     */
    public record Usage(UUID orderId, int credits, int remaining, boolean expired, List<UUID> dreamIds) {

        /** 1-based position of the dream among this order's dreams, or 0 when it did not use this order. */
        public int numberOf(UUID dreamId) {
            return dreamIds.indexOf(dreamId) + 1;
        }
    }

    /** Usage of every order of the user that added credits (keyed by order id). */
    @Transactional(readOnly = true)
    public Map<UUID, Usage> forUser(UUID userId) {
        Instant now = clock.instant();
        List<CreditLedgerEntry> rows = ledgerRepository.findByUserIdOrderByCreatedAtAsc(userId);
        Map<UUID, CreditLots.Lot> lots = new HashMap<>();
        for (CreditLots.Lot lot : CreditLots.replay(CreditExpiryService.toEntries(rows))) {
            lots.put(lot.id(), lot);
        }
        // a refund gives back one debit: the SUBMIT row it names (V40), or (older rows) the dream's only debit
        Set<UUID> refundedDebits = new HashSet<>();
        Set<UUID> refundedDreams = new HashSet<>();
        for (CreditLedgerEntry r : rows) {
            if (r.getReason() == LedgerReason.REFUND && r.getDreamId() != null) {
                if (r.getSourceId() != null) {
                    refundedDebits.add(r.getSourceId());
                } else {
                    refundedDreams.add(r.getDreamId());
                }
            }
        }

        Map<UUID, int[]> totals = new LinkedHashMap<>();       // orderId → {credits, remaining, expired}
        Map<UUID, List<UUID>> dreams = new HashMap<>();
        for (CreditLedgerEntry r : rows) {
            UUID orderId = r.getOrderId();
            if (orderId == null) {
                continue;
            }
            if (r.getReason() == LedgerReason.PURCHASE || r.getReason() == LedgerReason.REFUND) {
                int[] t = totals.computeIfAbsent(orderId, k -> new int[3]);
                CreditLots.Lot lot = lots.get(r.getId());
                boolean expired = lot != null && lot.expiresAt() != null && !lot.expiresAt().isAfter(now);
                if (r.getReason() == LedgerReason.PURCHASE) {
                    // an order approved again after a revoke (V40) has newer PURCHASE rows: the package's credits are
                    // the first one's (a later one may be smaller, without the leftover withdrawn before, V41)
                    if (t[0] == 0) {
                        t[0] = r.getDelta();
                    }
                    t[2] = expired ? 1 : 0;
                }
                if (lot != null && !expired) {
                    t[1] += lot.remaining();
                }
            } else if (r.getReason() == LedgerReason.SUBMIT && r.getDreamId() != null
                    && !refundedDebits.contains(r.getId()) && !refundedDreams.contains(r.getDreamId())) {
                List<UUID> list = dreams.computeIfAbsent(orderId, k -> new ArrayList<>());
                if (!list.contains(r.getDreamId())) {
                    list.add(r.getDreamId());
                }
            }
        }
        Map<UUID, Usage> out = new LinkedHashMap<>();
        totals.forEach((orderId, t) -> out.put(orderId, new Usage(orderId, t[0], Math.max(0, t[1]), t[2] == 1,
                List.copyOf(dreams.getOrDefault(orderId, List.of())))));
        return out;
    }

    /** Usage of one order of the user, or null when it added no credits (not paid). */
    @Transactional(readOnly = true)
    public Usage of(UUID userId, UUID orderId) {
        return orderId == null ? null : forUser(userId).get(orderId);
    }

    /**
     * The dream debits (SUBMIT rows) paid by {@code orderId} that were not given back, oldest first: the dreams this
     * payment currently pays for (V40 revoke).
     */
    @Transactional(readOnly = true)
    public List<CreditLedgerEntry> openDebits(UUID userId, UUID orderId) {
        List<CreditLedgerEntry> rows = ledgerRepository.findByUserIdOrderByCreatedAtAsc(userId);
        Set<UUID> refundedDebits = new HashSet<>();
        Set<UUID> refundedDreams = new HashSet<>();
        for (CreditLedgerEntry r : rows) {
            if (r.getReason() == LedgerReason.REFUND && r.getDreamId() != null) {
                if (r.getSourceId() != null) {
                    refundedDebits.add(r.getSourceId());
                } else {
                    refundedDreams.add(r.getDreamId());
                }
            }
        }
        List<CreditLedgerEntry> out = new ArrayList<>();
        for (CreditLedgerEntry r : rows) {
            if (r.getReason() == LedgerReason.SUBMIT && r.getDreamId() != null && orderId.equals(r.getOrderId())
                    && !refundedDebits.contains(r.getId()) && !refundedDreams.contains(r.getDreamId())) {
                out.add(r);
            }
        }
        return out;
    }
}
