package com.saadat.credits.service;

import com.saadat.common.domain.LedgerReason;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Replays a user's credit ledger as "lots" to know which credits are left in which purchase (pure, unit-tested).
 *
 * <ul>
 *   <li>Every positive row is a lot: PURCHASE (expires at {@code expiresAt}, null = never), MANUAL / BONUS / REFUND
 *       (never expire; a refund returns a credit as a fresh, non-expiring one).</li>
 *   <li>An EXPIRE row empties its {@code sourceId} lot.</li>
 *   <li>Any other negative row (SUBMIT, MANUAL correction) spends from lots still valid at that moment, the soonest
 *       to expire first, never-expiring lots last, older first on ties. If the valid lots cannot cover it (history from
 *       before expiry was enforced), the rest is taken from already expired lots so the totals always add up.</li>
 * </ul>
 */
public final class CreditLots {

    private CreditLots() {
    }

    /** One ledger row as the replay needs it. */
    public record Entry(UUID id, Instant createdAt, int delta, LedgerReason reason, Instant expiresAt, UUID sourceId,
                        boolean settled) {
    }

    /** What is left of one positive row after the replay. */
    public static final class Lot {
        private final UUID id;
        private final Instant createdAt;
        private final Instant expiresAt;
        private final boolean settled;
        private int remaining;

        Lot(UUID id, Instant createdAt, Instant expiresAt, boolean settled, int remaining) {
            this.id = id;
            this.createdAt = createdAt;
            this.expiresAt = expiresAt;
            this.settled = settled;
            this.remaining = remaining;
        }

        public UUID id() {
            return id;
        }

        public Instant createdAt() {
            return createdAt;
        }

        /** Null = never expires. */
        public Instant expiresAt() {
            return expiresAt;
        }

        /** The expiry job already handled this lot. */
        public boolean settled() {
            return settled;
        }

        public int remaining() {
            return remaining;
        }

        boolean validAt(Instant t) {
            return expiresAt == null || expiresAt.isAfter(t);
        }
    }

    /** The next expiry the user should know about: how many credits and when. */
    public record NextExpiry(int credits, Instant at) {
    }

    /** Soonest first, never-expiring last, then oldest first. */
    static final Comparator<Lot> SPEND_ORDER = Comparator
            .comparing(Lot::expiresAt, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(Lot::createdAt)
            .thenComparing(Lot::id);

    /** Replays the rows (any order; sorted here by time, credits before debits on equal times). */
    public static List<Lot> replay(List<Entry> rows) {
        List<Entry> sorted = new ArrayList<>(rows);
        sorted.sort(Comparator.comparing(Entry::createdAt)
                .thenComparing(e -> e.delta() > 0 ? 0 : 1)
                .thenComparing(Entry::id));
        List<Lot> lots = new ArrayList<>();
        Map<UUID, Lot> byId = new HashMap<>();
        for (Entry e : sorted) {
            if (e.delta() > 0) {
                Instant expires = e.reason() == LedgerReason.PURCHASE ? e.expiresAt() : null;
                Lot lot = new Lot(e.id(), e.createdAt(), expires, e.settled(), e.delta());
                lots.add(lot);
                byId.put(e.id(), lot);
            } else if (e.reason() == LedgerReason.EXPIRE) {
                Lot lot = e.sourceId() == null ? null : byId.get(e.sourceId());
                if (lot != null) {
                    lot.remaining = Math.max(0, lot.remaining + e.delta());
                }
            } else {
                spend(lots, -e.delta(), e.createdAt());
            }
        }
        return lots;
    }

    private static void spend(List<Lot> lots, int amount, Instant at) {
        List<Lot> valid = lots.stream().filter(l -> l.remaining > 0 && l.validAt(at)).sorted(SPEND_ORDER).toList();
        int left = take(valid, amount);
        if (left > 0) {
            List<Lot> rest = lots.stream().filter(l -> l.remaining > 0).sorted(SPEND_ORDER).toList();
            take(rest, left);
        }
    }

    private static int take(List<Lot> from, int amount) {
        int left = amount;
        for (Lot l : from) {
            if (left == 0) {
                break;
            }
            int n = Math.min(left, l.remaining);
            l.remaining -= n;
            left -= n;
        }
        return left;
    }

    /** Lots past expiry at {@code now} that the job has not settled yet (remaining may be 0). */
    public static List<Lot> due(List<Lot> lots, Instant now) {
        return lots.stream()
                .filter(l -> l.expiresAt != null && !l.expiresAt.isAfter(now) && !l.settled)
                .toList();
    }

    /** Credits that are already past expiry but not yet written off by the job (to hide them from the balance). */
    public static int pendingExpired(List<Lot> lots, Instant now) {
        return due(lots, now).stream().mapToInt(Lot::remaining).sum();
    }

    /**
     * The soonest upcoming expiry within {@code window} of {@code now}: its date, and the credits expiring within the
     * same day (24 hours) of it. Null when nothing expires in the window.
     */
    public static NextExpiry next(List<Lot> lots, Instant now, Duration window) {
        List<Lot> upcoming = lots.stream()
                .filter(l -> l.remaining > 0 && l.expiresAt != null && l.expiresAt.isAfter(now))
                .sorted(SPEND_ORDER)
                .toList();
        if (upcoming.isEmpty()) {
            return null;
        }
        Instant first = upcoming.get(0).expiresAt;
        if (first.isAfter(now.plus(window))) {
            return null;
        }
        Instant dayEnd = first.plus(Duration.ofDays(1));
        int credits = upcoming.stream().filter(l -> l.expiresAt.isBefore(dayEnd)).mapToInt(Lot::remaining).sum();
        return new NextExpiry(credits, first);
    }
}
