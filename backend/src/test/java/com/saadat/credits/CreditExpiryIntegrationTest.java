package com.saadat.credits;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.saadat.IntegrationTestBase;
import com.saadat.common.api.ApiPaths;
import com.saadat.common.domain.LedgerReason;
import com.saadat.common.domain.Role;
import com.saadat.credits.service.CreditExpiryService;
import com.saadat.credits.service.CreditService;
import com.saadat.payments.domain.CreditLedgerEntry;
import com.saadat.payments.repo.CreditLedgerRepository;
import com.saadat.users.domain.User;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class CreditExpiryIntegrationTest extends IntegrationTestBase {

    @Autowired
    CreditService creditService;

    @Autowired
    CreditExpiryService expiryService;

    @Autowired
    CreditLedgerRepository ledgerRepository;

    @Autowired
    Clock clock;

    @Test
    void expiredPurchaseIsWrittenOffBeforeSpendingAndOnlyOnce() {
        User user = createUser("expiry", Role.USER);
        Instant now = clock.instant();
        CreditLedgerEntry old = creditService.addPurchase(user.getId(), 3, null, now.minus(Duration.ofDays(1)));
        creditService.addPurchase(user.getId(), 2, null, now.plus(Duration.ofDays(30)));
        assertThat(ledgerRepository.balance(user.getId())).isEqualTo(5);

        // spending first writes off the expired purchase, then uses the valid one
        creditService.consume(user.getId(), 1, LedgerReason.SUBMIT, null, null);
        assertThat(ledgerRepository.balance(user.getId())).isEqualTo(1);

        List<CreditLedgerEntry> expired = ledgerRepository.findByUserIdOrderByCreatedAtAsc(user.getId()).stream()
                .filter(e -> e.getReason() == LedgerReason.EXPIRE).toList();
        assertThat(expired).hasSize(1);
        assertThat(expired.get(0).getDelta()).isEqualTo(-3);
        assertThat(expired.get(0).getSourceId()).isEqualTo(old.getId());
        assertThat(ledgerRepository.findById(old.getId()).orElseThrow().getExpirySettledAt()).isNotNull();

        // the job finds nothing left to do for this user
        assertThat(expiryService.usersWithDueExpiry()).doesNotContain(user.getId());
        assertThat(expiryService.expireDueLocked(user.getId())).isZero();
        assertThat(ledgerRepository.balance(user.getId())).isEqualTo(1);
    }

    @Test
    void jobWritesOffOnlyTheUnusedPartAndDashboardShowsTheNextExpiry() throws Exception {
        User user = createUser("expiry-job", Role.USER);
        Instant now = clock.instant();
        creditService.addPurchase(user.getId(), 2, null, now.minus(Duration.ofHours(1)));
        creditService.addPurchase(user.getId(), 4, null, now.plus(Duration.ofDays(10)));

        // before the job runs the dashboard already hides the expired credits
        mvc.perform(get(ApiPaths.Me.DASHBOARD).header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.credits").value(4))
                .andExpect(jsonPath("$.nextExpiry.credits").value(4));

        // (the hourly job may have settled it already: the end state is what matters)
        expiryService.expireDueLocked(user.getId());
        assertThat(ledgerRepository.balance(user.getId())).isEqualTo(4);
        List<CreditLedgerEntry> expired = ledgerRepository.findByUserIdOrderByCreatedAtAsc(user.getId()).stream()
                .filter(e -> e.getReason() == LedgerReason.EXPIRE).toList();
        assertThat(expired).hasSize(1);
        assertThat(expired.get(0).getDelta()).isEqualTo(-2);
        assertThat(expiryService.expireDueLocked(user.getId())).isZero();
    }
}
