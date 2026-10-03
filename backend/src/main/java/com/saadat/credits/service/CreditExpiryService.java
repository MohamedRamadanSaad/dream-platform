package com.saadat.credits.service;

import com.saadat.common.domain.LedgerReason;
import com.saadat.payments.domain.CreditLedgerEntry;
import com.saadat.payments.repo.CreditLedgerRepository;
import com.saadat.settings.SettingKeys;
import com.saadat.settings.SettingsService;
import com.saadat.users.repo.UserRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Purchased credits expire (V23): the unused part of a purchase past its {@code expires_at} becomes one EXPIRE row.
 * Run by {@link CreditExpiryJob} and right before every debit ({@link CreditService}), so an expired credit can
 * never be spent even between two job runs.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CreditExpiryService {

    static final int DEFAULT_NOTICE_DAYS = 60;

    private final CreditLedgerRepository ledgerRepository;
    private final UserRepository userRepository;
    private final SettingsService settings;
    private final Clock clock;

    /** What the user dashboard needs: balance without already-expired credits, and the next expiry to announce. */
    public record View(int balance, CreditLots.NextExpiry nextExpiry) {
    }

    /**
     * Writes EXPIRE rows for every purchase of the user that is past expiry and not settled. The caller must hold the
     * user row lock (as every debit does), so this runs inside the caller's transaction.
     *
     * @return the number of credits written off
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int expireDue(UUID userId) {
        Instant now = clock.instant();
        List<CreditLedgerEntry> rows = ledgerRepository.findByUserIdOrderByCreatedAtAsc(userId);
        List<CreditLots.Lot> due = CreditLots.due(CreditLots.replay(toEntries(rows)), now);
        if (due.isEmpty()) {
            return 0;
        }
        Map<UUID, CreditLedgerEntry> byId = rows.stream()
                .collect(Collectors.toMap(CreditLedgerEntry::getId, Function.identity()));
        int total = 0;
        for (CreditLots.Lot lot : due) {
            if (lot.remaining() > 0) {
                CreditLedgerEntry e = new CreditLedgerEntry();
                e.setUserId(userId);
                e.setDelta(-lot.remaining());
                e.setReason(LedgerReason.EXPIRE);
                e.setSourceId(lot.id());
                e.setCreatedAt(now);
                ledgerRepository.save(e);
                total += lot.remaining();
            }
            CreditLedgerEntry purchase = byId.get(lot.id());
            purchase.setExpirySettledAt(now);
            ledgerRepository.save(purchase);
        }
        if (total > 0) {
            log.info("Expired {} credit(s) of user {}", total, userId);
        }
        return total;
    }

    /** Locks the user and settles due expiries in a transaction of its own (used by the job, one user at a time). */
    @Transactional
    public int expireDueLocked(UUID userId) {
        if (userRepository.findByIdForUpdate(userId).isEmpty()) {
            return 0;
        }
        return expireDue(userId);
    }

    /** Users with something to settle now. */
    @Transactional(readOnly = true)
    public List<UUID> usersWithDueExpiry() {
        return ledgerRepository.findUserIdsWithDueExpiry(clock.instant());
    }

    @Transactional(readOnly = true)
    public View view(UUID userId) {
        Instant now = clock.instant();
        List<CreditLedgerEntry> rows = ledgerRepository.findByUserIdOrderByCreatedAtAsc(userId);
        List<CreditLots.Lot> lots = CreditLots.replay(toEntries(rows));
        int balance = rows.stream().mapToInt(CreditLedgerEntry::getDelta).sum() - CreditLots.pendingExpired(lots, now);
        int days = settings.getInt(SettingKeys.CREDITS_EXPIRY_NOTICE_DAYS, DEFAULT_NOTICE_DAYS);
        return new View(Math.max(0, balance), CreditLots.next(lots, now, Duration.ofDays(Math.max(0, days))));
    }

    static List<CreditLots.Entry> toEntries(List<CreditLedgerEntry> rows) {
        return rows.stream()
                .map(r -> new CreditLots.Entry(r.getId(), r.getCreatedAt(), r.getDelta(), r.getReason(),
                        r.getExpiresAt(), r.getSourceId(), r.getExpirySettledAt() != null))
                .toList();
    }
}
