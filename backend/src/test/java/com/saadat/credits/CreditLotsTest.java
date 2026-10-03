package com.saadat.credits;

import static org.assertj.core.api.Assertions.assertThat;

import com.saadat.common.domain.LedgerReason;
import com.saadat.credits.service.CreditLots;
import com.saadat.credits.service.CreditLots.Entry;
import com.saadat.credits.service.CreditLots.Lot;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CreditLotsTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private static Instant day(int n) {
        return T0.plus(Duration.ofDays(n));
    }

    private static Entry purchase(UUID id, int at, int credits, Integer expiresDay) {
        return new Entry(id, day(at), credits, LedgerReason.PURCHASE, expiresDay == null ? null : day(expiresDay), null,
                false);
    }

    private static Entry submit(int at) {
        return new Entry(UUID.randomUUID(), day(at), -1, LedgerReason.SUBMIT, null, null, false);
    }

    private static Lot lot(List<Lot> lots, UUID id) {
        return lots.stream().filter(l -> l.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void partlyUsedPurchaseLeavesTheRestToExpire() {
        UUID a = UUID.randomUUID();
        List<Lot> lots = CreditLots.replay(List.of(purchase(a, 0, 3, 365), submit(10)));
        assertThat(lot(lots, a).remaining()).isEqualTo(2);
        assertThat(CreditLots.due(lots, day(364))).isEmpty();
        assertThat(CreditLots.due(lots, day(365))).extracting(Lot::id).containsExactly(a);
        assertThat(CreditLots.pendingExpired(lots, day(400))).isEqualTo(2);
    }

    @Test
    void soonestExpiringPurchaseIsSpentFirst() {
        UUID later = UUID.randomUUID();
        UUID sooner = UUID.randomUUID();
        // the later-expiring purchase was bought first, the sooner one second: the sooner one is still used first
        List<Lot> lots = CreditLots.replay(List.of(
                purchase(later, 0, 2, 400), purchase(sooner, 5, 2, 200), submit(10), submit(11)));
        assertThat(lot(lots, sooner).remaining()).isZero();
        assertThat(lot(lots, later).remaining()).isEqualTo(2);
    }

    @Test
    void expiredLotIsNotSpentWhenAValidOneExists() {
        UUID old = UUID.randomUUID();
        UUID fresh = UUID.randomUUID();
        List<Lot> lots = CreditLots.replay(List.of(purchase(old, 0, 1, 30), purchase(fresh, 20, 1, 400), submit(40)));
        assertThat(lot(lots, old).remaining()).isEqualTo(1);
        assertThat(lot(lots, fresh).remaining()).isZero();
    }

    @Test
    void manualCreditsAndRefundsNeverExpireAndAreUsedLast() {
        UUID p = UUID.randomUUID();
        UUID manual = UUID.randomUUID();
        UUID refund = UUID.randomUUID();
        List<Entry> rows = new ArrayList<>();
        rows.add(new Entry(manual, day(0), 2, LedgerReason.MANUAL, null, null, false));
        rows.add(purchase(p, 1, 1, 100));
        rows.add(submit(2));
        rows.add(new Entry(refund, day(3), 1, LedgerReason.REFUND, null, null, false));
        List<Lot> lots = CreditLots.replay(rows);
        assertThat(lot(lots, p).remaining()).isZero();
        assertThat(lot(lots, manual).remaining()).isEqualTo(2);
        assertThat(lot(lots, refund).remaining()).isEqualTo(1);
        assertThat(lot(lots, manual).expiresAt()).isNull();
        assertThat(lot(lots, refund).expiresAt()).isNull();
        // only the (fully used) purchase is due, for settling; nothing is left to write off
        assertThat(CreditLots.due(lots, day(1000))).extracting(Lot::id).containsExactly(p);
        assertThat(CreditLots.pendingExpired(lots, day(1000))).isZero();
    }

    @Test
    void purchaseWithoutValidityNeverExpires() {
        UUID p = UUID.randomUUID();
        List<Lot> lots = CreditLots.replay(List.of(purchase(p, 0, 2, null)));
        assertThat(CreditLots.due(lots, day(5000))).isEmpty();
        assertThat(CreditLots.next(lots, day(1), Duration.ofDays(60))).isNull();
    }

    @Test
    void expireRowEmptiesItsLotAndSettledLotsAreNotDueAgain() {
        UUID p = UUID.randomUUID();
        List<Entry> rows = List.of(
                new Entry(p, day(0), 3, LedgerReason.PURCHASE, day(30), null, true),
                new Entry(UUID.randomUUID(), day(30), -3, LedgerReason.EXPIRE, null, p, false));
        List<Lot> lots = CreditLots.replay(rows);
        assertThat(lot(lots, p).remaining()).isZero();
        assertThat(CreditLots.due(lots, day(31))).isEmpty();
        assertThat(CreditLots.pendingExpired(lots, day(31))).isZero();
    }

    @Test
    void nextExpiryCountsCreditsDueTheSameDayWithinTheWindow() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        List<Lot> lots = CreditLots.replay(List.of(
                purchase(a, 0, 2, 100), purchase(b, 0, 1, 100), purchase(c, 0, 5, 300)));
        assertThat(CreditLots.next(lots, day(20), Duration.ofDays(60))).isNull();
        CreditLots.NextExpiry next = CreditLots.next(lots, day(50), Duration.ofDays(60));
        assertThat(next).isNotNull();
        assertThat(next.credits()).isEqualTo(3);
        assertThat(next.at()).isEqualTo(day(100));
    }
}
