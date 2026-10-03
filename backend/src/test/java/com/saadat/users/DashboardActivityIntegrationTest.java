package com.saadat.users;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.saadat.IntegrationTestBase;
import com.saadat.common.api.ApiPaths;
import com.saadat.common.domain.Currency;
import com.saadat.common.domain.Device;
import com.saadat.common.domain.LedgerReason;
import com.saadat.common.domain.OrderStatus;
import com.saadat.common.domain.PaymentProviderType;
import com.saadat.common.domain.Role;
import com.saadat.credits.service.CreditService;
import com.saadat.payments.domain.Order;
import com.saadat.payments.repo.OrderRepository;
import com.saadat.tracking.domain.PageView;
import com.saadat.tracking.repo.PageViewRepository;
import com.saadat.users.domain.User;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;

/** GET /me/dashboard activity tiles: credits used, last visit before this one, last package bought. */
class DashboardActivityIntegrationTest extends IntegrationTestBase {

    @Autowired
    CreditService creditService;

    @Autowired
    OrderRepository orderRepository;

    @Autowired
    PageViewRepository pageViewRepository;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void aNewUserHasNoActivityYet() throws Exception {
        User user = createUser("dash-new", Role.USER);
        mvc.perform(get(ApiPaths.Me.DASHBOARD).header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usedCredits").value(0))
                .andExpect(jsonPath("$.lastVisitAt").doesNotExist())
                .andExpect(jsonPath("$.lastPackage").doesNotExist());
    }

    @Test
    void usedCreditsLastVisitAndLastPackage() throws Exception {
        User user = createUser("dash-activity", Role.USER);
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);

        // credits: +5 bonus, three dreams sent, a refund not linked to a dream and a manual correction do not count
        creditService.add(user.getId(), 5, LedgerReason.BONUS, null, "test", null);
        for (int i = 0; i < 3; i++) {
            creditService.consume(user.getId(), 1, LedgerReason.SUBMIT, null, null);
        }
        creditService.add(user.getId(), 1, LedgerReason.REFUND, null, "goodwill", null);
        creditService.add(user.getId(), -1, LedgerReason.MANUAL, null, "correction", null);

        // orders: the newest PAID one wins over an older paid one and a newer failed one
        orderRepository.saveAll(List.of(
                order(user, "Old pack", 1, OrderStatus.SUCCESS, now.minus(Duration.ofDays(2))),
                order(user, "Three dreams", 3, OrderStatus.SUCCESS, now.minus(Duration.ofDays(1))),
                order(user, "Failed pack", 5, OrderStatus.FAILED, null)));

        // visits: three days ago, two hours ago, and the current one
        Instant previous = now.minus(Duration.ofHours(2));
        pageViewRepository.saveAll(List.of(
                view(user, "dash-v-old", now.minus(Duration.ofDays(3))),
                view(user, "dash-v-prev", previous),
                view(user, "dash-v-now", now.minus(Duration.ofMinutes(1)))));

        JsonNode withVisit = dashboard(user, "dash-v-now");
        assertThat(withVisit.get("usedCredits").asLong()).isEqualTo(3);
        assertThat(withVisit.get("credits").asInt()).isEqualTo(2);
        assertThat(Instant.parse(withVisit.get("lastVisitAt").asText())).isEqualTo(previous);
        JsonNode pack = withVisit.get("lastPackage");
        assertThat(pack.get("name").asText()).isEqualTo("Three dreams");
        assertThat(pack.get("credits").asInt()).isEqualTo(3);
        assertThat(pack.get("currency").asText()).isEqualTo("SAR");
        assertThat(new BigDecimal(pack.get("amount").asText())).isEqualByComparingTo("120");
        assertThat(Instant.parse(pack.get("paidAt").asText())).isEqualTo(now.minus(Duration.ofDays(1)));
        assertThat(pack.get("orderId").asText()).isNotBlank();

        // an old app version sends no visit id: the views of the last 30 minutes are treated as this visit
        JsonNode withoutVisit = dashboard(user, null);
        assertThat(Instant.parse(withoutVisit.get("lastVisitAt").asText())).isEqualTo(previous);
    }

    private JsonNode dashboard(User user, String visit) throws Exception {
        var request = get(ApiPaths.Me.DASHBOARD).header(HttpHeaders.AUTHORIZATION, bearer(user));
        if (visit != null) {
            request = request.param("visit", visit);
        }
        String body = mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private static Order order(User user, String name, int credits, OrderStatus status, Instant paidAt) {
        Order o = new Order();
        o.setUserId(user.getId());
        o.setPackageNameSnapshot(name);
        o.setCredits(credits);
        o.setAmount(BigDecimal.valueOf(40L * credits));
        o.setCurrency(Currency.SAR);
        o.setProvider(PaymentProviderType.MOCK);
        o.setStatus(status);
        o.setPaidAt(paidAt);
        o.setExpiresAt(Instant.now().plus(Duration.ofHours(1)));
        return o;
    }

    private static PageView view(User user, String visit, Instant at) {
        PageView v = new PageView();
        v.setPath("/me");
        v.setSessionId(visit);
        v.setVisitorId("dash-browser");
        v.setUserId(user.getId());
        v.setCountryCode("SA");
        v.setDevice(Device.MOBILE);
        v.setCreatedAt(at);
        return v;
    }
}
