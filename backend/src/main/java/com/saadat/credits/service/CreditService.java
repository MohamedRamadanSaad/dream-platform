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
import java.util.List;
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
        int balance = lockAndGetBalance(userId);
        if (balance < 1) {
            throw new PaymentRequiredException(1 - balance);
        }
        List<CreditLedgerEntry> rows = ledgerRepository.findByUserIdOrderByCreatedAtAsc(userId);
        CreditLots.Lot lot = CreditLots.nextToSpend(CreditLots.replay(CreditExpiryService.toEntries(rows)),
                clock.instant());
        CreditLedgerEntry source = lot == null ? null
                : rows.stream().filter(r -> r.getId().equals(lot.id())).findFirst().orElse(null);
        CreditLedgerEntry e = save(userId, -1, LedgerReason.SUBMIT, source == null ? null : source.getOrderId(),
                dreamId, null, null);
        e.setSourceId(source == null ? null : source.getId());
        return ledgerRepository.save(e);
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

    /** Credits of a paid order; they expire at {@code expiresAt} (null = never). */
    @Transactional
    public CreditLedgerEntry addPurchase(UUID userId, int credits, UUID orderId, Instant expiresAt) {
        CreditLedgerEntry e = add(userId, credits, LedgerReason.PURCHASE, orderId, null, null);
        e.setExpiresAt(expiresAt);
        return ledgerRepository.save(e);
    }

    private CreditLedgerEntry save(UUID userId, int delta, LedgerReason reason, UUID orderId, UUID dreamId,
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
        return ledgerRepository.save(e);
    }
}
