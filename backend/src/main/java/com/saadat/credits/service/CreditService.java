package com.saadat.credits.service;

import com.saadat.common.domain.LedgerReason;
import com.saadat.common.error.NotFoundException;
import com.saadat.common.error.PaymentRequiredException;
import com.saadat.common.error.ValidationException;
import com.saadat.credits.api.CreditLedgerEntryDto;
import com.saadat.credits.api.CreditsSummaryDto;
import com.saadat.payments.domain.CreditLedgerEntry;
import com.saadat.payments.repo.CreditLedgerRepository;
import com.saadat.users.repo.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Credits are a ledger, never a counter (contract rule 2): balance = SUM(delta).
 * Every debit first locks the user row ({@code SELECT … FOR UPDATE}) and re-reads the balance, so two
 * concurrent debits for the same user are serialized and can never overdraw.
 */
@Service
@RequiredArgsConstructor
public class CreditService {

    private final CreditLedgerRepository ledgerRepository;
    private final UserRepository userRepository;
    private final Clock clock;
    private final CreditExpiryService expiryService;

    @Transactional(readOnly = true)
    public int balance(UUID userId) {
        return ledgerRepository.balance(userId);
    }

    @Transactional(readOnly = true)
    public CreditsSummaryDto summary(UUID userId) {
        return new CreditsSummaryDto(ledgerRepository.balance(userId),
                ledgerRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                        .map(CreditLedgerEntryDto::from).toList());
    }

    /**
     * Locks the user row for the rest of the current transaction and returns the fresh balance.
     * Must be called inside a transaction (MANDATORY).
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public int lockAndGetBalance(UUID userId) {
        userRepository.findByIdForUpdate(userId).orElseThrow(() -> NotFoundException.of("User", userId));
        // purchases past their expiry are written off first, so an expired credit is never spent
        expiryService.expireDue(userId);
        return ledgerRepository.balance(userId);
    }

    /**
     * Debits {@code n} credits in ONE ledger row. Locks the user row first, re-checks the balance and throws
     * 402 INSUFFICIENT_CREDITS with the missing amount when short.
     */
    @Transactional
    public CreditLedgerEntry consume(UUID userId, int n, LedgerReason reason, UUID dreamId, UUID orderId) {
        if (n < 1) {
            throw new ValidationException("Amount must be positive", "INVALID_AMOUNT");
        }
        int balance = lockAndGetBalance(userId);
        if (balance < n) {
            throw new PaymentRequiredException(n - balance);
        }
        return save(userId, -n, reason, orderId, dreamId, null, null);
    }

    /**
     * One credit for a dream (SUBMIT), taken from the lot that holds it: the purchase (or refund) whose credits are
     * spent first. The row carries that lot ({@code source_id}) and its order ({@code order_id}), so every dream of a
     * 3-dream package points at the same payment, and the payment's counter goes 3 → 2 → 1 → 0. Credits given by hand
     * (MANUAL / BONUS) have no order. Locks the user row; 402 INSUFFICIENT_CREDITS when nothing is left.
     */
    @Transactional
    public CreditLedgerEntry consumeForDream(UUID userId, UUID dreamId) {
        return consumeForDream(userId, dreamId, null);
    }

    /**
     * {@link #consumeForDream(UUID, UUID)} that takes the credit from a lot of {@code preferredOrderId} when one still
     * has credits (a held dream resumed by the re-approval of its order, V40), else from the usual next lot.
     */
    @Transactional
    public CreditLedgerEntry consumeForDream(UUID userId, UUID dreamId, UUID preferredOrderId) {
        int balance = lockAndGetBalance(userId);
        if (balance < 1) {
            throw new PaymentRequiredException(1 - balance);
        }
        Instant now = clock.instant();
        List<CreditLedgerEntry> rows = ledgerRepository.findByUserIdOrderByCreatedAtAsc(userId);
        Map<UUID, CreditLedgerEntry> byId = new HashMap<>();
        rows.forEach(r -> byId.put(r.getId(), r));
        List<CreditLots.Lot> lots = CreditLots.replay(CreditExpiryService.toEntries(rows));
        CreditLots.Lot lot = null;
        if (preferredOrderId != null) {
            lot = CreditLots.nextToSpend(lots.stream()
                    .filter(l -> byId.containsKey(l.id())
                            && preferredOrderId.equals(byId.get(l.id()).getOrderId()))
                    .toList(), now);
            if (lot != null && !lot.validAt(now)) {
                lot = null;
            }
        }
        if (lot == null) {
            lot = CreditLots.nextToSpend(lots, now);
        }
        CreditLedgerEntry source = lot == null ? null : byId.get(lot.id());
        // source_id is part of the SUBMIT guard (V40): it is set before the row is written
        CreditLedgerEntry e = entry(userId, -1, LedgerReason.SUBMIT, source == null ? null : source.getOrderId(),
                dreamId, null, null);
        e.setSourceId(source == null ? null : source.getId());
        return ledgerRepository.save(e);
    }

    /**
     * One credit for a held dream resumed by the re-approval of {@code orderId} (V40): taken from that order's newest
     * PURCHASE lot even when it is already past its expiry (the order paid this dream before the credits expired),
     * else from any valid lot. Locks the user row but writes off no expiry first (the caller does it afterwards).
     * Returns null, without writing anything, when no credit is available.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public CreditLedgerEntry consumeForResume(UUID userId, UUID dreamId, UUID orderId) {
        userRepository.findByIdForUpdate(userId).orElseThrow(() -> NotFoundException.of("User", userId));
        Instant now = clock.instant();
        List<CreditLedgerEntry> rows = ledgerRepository.findByUserIdOrderByCreatedAtAsc(userId);
        Map<UUID, CreditLedgerEntry> byId = new HashMap<>();
        rows.forEach(r -> byId.put(r.getId(), r));
        List<CreditLots.Lot> lots = CreditLots.replay(CreditExpiryService.toEntries(rows));
        CreditLots.Lot lot = lots.stream()
                .filter(l -> l.remaining() > 0 && byId.containsKey(l.id())
                        && byId.get(l.id()).getReason() == LedgerReason.PURCHASE
                        && orderId.equals(byId.get(l.id()).getOrderId()))
                .max(java.util.Comparator.comparing(CreditLots.Lot::createdAt))
                .orElse(null);
        if (lot == null) {
            lot = lots.stream().filter(l -> l.remaining() > 0 && l.validAt(now) && !l.settled())
                    .min(CreditLots.SPEND_ORDER).orElse(null);
        }
        if (lot == null) {
            return null;
        }
        CreditLedgerEntry source = byId.get(lot.id());
        CreditLedgerEntry e = entry(userId, -1, LedgerReason.SUBMIT, source.getOrderId(), dreamId, null, null);
        e.setSourceId(source.getId());
        return ledgerRepository.save(e);
    }

    /**
     * Gives back the credit of one dream debit ({@code submit}, a SUBMIT row) once: REFUND +1 with the same order and
     * {@code source_id} = that debit (V40 guard: one refund per debit). Returns null when it was already given back.
     */
    @Transactional
    public CreditLedgerEntry refundDebit(CreditLedgerEntry submit, String note, UUID createdBy) {
        if (submit == null || submit.getReason() != LedgerReason.SUBMIT
                || ledgerRepository.existsBySourceIdAndReason(submit.getId(), LedgerReason.REFUND)) {
            return null;
        }
        if (!userRepository.existsById(submit.getUserId())) {
            throw NotFoundException.of("User", submit.getUserId());
        }
        CreditLedgerEntry e = entry(submit.getUserId(), 1, LedgerReason.REFUND, submit.getOrderId(),
                submit.getDreamId(), note, createdBy);
        e.setSourceId(submit.getId());
        return ledgerRepository.save(e);
    }

    /**
     * Withdraws what is left of every lot of {@code orderId} (its PURCHASE rows and the REFUND rows that gave its
     * credits back): one REVOKE row per lot with {@code source_id} = that lot (V40). Expired credits were already
     * written off by {@link #lockAndGetBalance} and are not removed twice; the balance never goes below zero. The
     * caller holds the user lock. Returns the number of credits withdrawn.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public int revokeOrderCredits(UUID userId, UUID orderId, String note, UUID createdBy) {
        int balance = lockAndGetBalance(userId);
        List<CreditLedgerEntry> rows = ledgerRepository.findByUserIdOrderByCreatedAtAsc(userId);
        Map<UUID, CreditLedgerEntry> byId = new HashMap<>();
        rows.forEach(r -> byId.put(r.getId(), r));
        int total = 0;
        for (CreditLots.Lot lot : CreditLots.replay(CreditExpiryService.toEntries(rows))) {
            CreditLedgerEntry row = byId.get(lot.id());
            if (row == null || !orderId.equals(row.getOrderId()) || lot.remaining() <= 0) {
                continue;
            }
            int n = Math.min(lot.remaining(), balance - total);
            if (n <= 0) {
                break;
            }
            CreditLedgerEntry e = entry(userId, -n, LedgerReason.REVOKE, orderId, null, note, createdBy);
            e.setSourceId(lot.id());
            ledgerRepository.save(e);
            total += n;
        }
        return total;
    }

    /** Credits (or, for MANUAL corrections, debits) the ledger. A negative delta may not overdraw. */
    @Transactional
    public CreditLedgerEntry add(UUID userId, int delta, LedgerReason reason, UUID orderId, String note,
                                 UUID createdBy) {
        return add(userId, delta, reason, orderId, null, note, createdBy);
    }

    @Transactional
    public CreditLedgerEntry add(UUID userId, int delta, LedgerReason reason, UUID orderId, UUID dreamId, String note,
                                 UUID createdBy) {
        if (delta == 0) {
            throw new ValidationException("delta must not be zero", "INVALID_AMOUNT");
        }
        if (delta < 0) {
            int balance = lockAndGetBalance(userId);
            if (balance + delta < 0) {
                throw new ValidationException("Balance cannot become negative", "NEGATIVE_BALANCE");
            }
        } else if (!userRepository.existsById(userId)) {
            throw NotFoundException.of("User", userId);
        }
        return save(userId, delta, reason, orderId, dreamId, note, createdBy);
    }

    /**
     * Credits of a paid order; they expire at {@code expiresAt} (null = never). An order approved again after its
     * approval was taken back (V40) gets a new PURCHASE row whose {@code source_id} is the previous one and whose
     * expiry is the FIRST approval's (a re-approval never revives expired credits; past it, they expire at once).
     */
    @Transactional
    public CreditLedgerEntry addPurchase(UUID userId, int credits, UUID orderId, Instant expiresAt) {
        if (credits <= 0) {
            throw new ValidationException("delta must not be zero", "INVALID_AMOUNT");
        }
        if (!userRepository.existsById(userId)) {
            throw NotFoundException.of("User", userId);
        }
        List<CreditLedgerEntry> earlier = orderId == null ? List.of()
                : ledgerRepository.findAllByOrderIdAndReasonOrderByCreatedAtAsc(orderId, LedgerReason.PURCHASE);
        CreditLedgerEntry e = entry(userId, credits, LedgerReason.PURCHASE, orderId, null, null, null);
        e.setExpiresAt(earlier.isEmpty() ? expiresAt : earlier.get(0).getExpiresAt());
        e.setSourceId(earlier.isEmpty() ? null : earlier.get(earlier.size() - 1).getId());
        return ledgerRepository.save(e);
    }

    private CreditLedgerEntry save(UUID userId, int delta, LedgerReason reason, UUID orderId, UUID dreamId,
                                   String note, UUID createdBy) {
        return ledgerRepository.save(entry(userId, delta, reason, orderId, dreamId, note, createdBy));
    }

    /** A new, unsaved row. */
    private CreditLedgerEntry entry(UUID userId, int delta, LedgerReason reason, UUID orderId, UUID dreamId,
                                    String note, UUID createdBy) {
        CreditLedgerEntry e = new CreditLedgerEntry();
        e.setUserId(userId);
        e.setDelta(delta);
        e.setReason(reason);
        e.setOrderId(orderId);
        e.setDreamId(dreamId);
        e.setNote(note);
        e.setCreatedBy(createdBy);
        e.setCreatedAt(clock.instant());
        return e;
    }
}
