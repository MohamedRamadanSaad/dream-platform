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
