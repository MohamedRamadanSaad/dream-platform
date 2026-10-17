package com.saadat.admin.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.saadat.IntegrationTestBase;
import com.saadat.common.api.ApiPaths;
import com.saadat.common.domain.Currency;
import com.saadat.common.domain.OrderStatus;
import com.saadat.common.domain.PaymentProviderType;
import com.saadat.common.domain.Role;
import com.saadat.payments.domain.Order;
import com.saadat.payments.repo.OrderRepository;
import com.saadat.payments.service.OrderNumbers;
import com.saadat.users.domain.User;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/** The Users screen shows a user's money in his own currency: EGP for Egypt, USD for everyone else; ranked in EGP. */
class UsersMoneyIntegrationTest extends IntegrationTestBase {

    /** Seeded package p1 (1 credit). */
    private static final String PACKAGE_ONE = "22222222-2222-4222-8222-000000000001";

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    OrderRepository orderRepository;

    @Autowired
    OrderNumbers orderNumbers;

    @Test
    void egyptiansAreShownInEgpOthersInUsdAndTheRankingIsInEgp() throws Exception {
        User interpreter = createUser("egpusers-interpreter", Role.INTERPRETER);
        String tag = "egpusers" + (System.nanoTime() % 1_000_000);

        // an Egyptian buyer pays an InstaPay order in EGP
        User egyptian = createUser(tag + "-eg", Role.USER);
        egyptian.setCountryCode("EG");
        egyptian = userRepository.save(egyptian);
        JsonNode checkout = json(mvc.perform(post(ApiPaths.Checkout.ROOT).header("Authorization", bearer(egyptian))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("packageId", PACKAGE_ONE))))
                .andExpect(status().isOk()));
        UUID egOrder = UUID.fromString(checkout.get("orderId").asText());
        String reference = String.valueOf(100_000_000_000L + (long) (Math.random() * 899_999_999_999L));
        mvc.perform(post(ApiPaths.Me.ORDERS + "/" + egOrder + "/instapay").header("Authorization", bearer(egyptian))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("reference", reference, "senderName", "BUYER NAME"))))
                .andExpect(status().isOk());
        mvc.perform(post(ApiPaths.Admin.INSTAPAY_TRANSFERS + "/" + egOrder + "/approve")
                        .header("Authorization", bearer(interpreter)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reference\":\"" + reference + "\"}"))
                .andExpect(status().isOk());
        BigDecimal egpAmount = orderRepository.findById(egOrder).orElseThrow().getAmount();

        // a buyer from the US paid 30 USD, settled at 100 EGP: more in USD, less in EGP than the Egyptian
        User american = createUser(tag + "-us", Role.USER);
        american.setCountryCode("US");
        american = userRepository.save(american);
        Order usd = new Order();
        usd.setOrderNumber(orderNumbers.next());
        usd.setUserId(american.getId());
        usd.setPackageNameSnapshot("Test package");
        usd.setCredits(1);
        usd.setAmount(new BigDecimal("30.00"));
        usd.setCurrency(Currency.USD);
        usd.setProvider(PaymentProviderType.MOCK);
        usd.setStatus(OrderStatus.SUCCESS);
        usd.setCountryCode("US");
        Instant now = Instant.now();
        usd.setCreatedAt(now);
        usd.setPaidAt(now);
        usd.setExpiresAt(now.plus(Duration.ofHours(1)));
        usd.setSettledAmountEgp(new BigDecimal("100.00"));
        orderRepository.save(usd);
        // a failed order never counts
        Order failed = new Order();
        failed.setOrderNumber(orderNumbers.next());
        failed.setUserId(american.getId());
        failed.setPackageNameSnapshot("Test package");
        failed.setCredits(1);
        failed.setAmount(new BigDecimal("500.00"));
        failed.setCurrency(Currency.USD);
        failed.setProvider(PaymentProviderType.MOCK);
        failed.setStatus(OrderStatus.FAILED);
        failed.setCountryCode("US");
        failed.setCreatedAt(now);
        failed.setExpiresAt(now.plus(Duration.ofHours(1)));
        orderRepository.save(failed);

        JsonNode page = json(mvc.perform(get(ApiPaths.Admin.ANALYTICS_USERS).param("list", "top-paying").param("q", tag)
                        .header("Authorization", bearer(interpreter)))
                .andExpect(status().isOk()));
        List<String> ids = new ArrayList<>();
        page.get("items").forEach(i -> ids.add(i.get("id").asText()));
        // ranked in EGP: 199-ish EGP before 100 EGP (in USD it would be the other way round)
        assertThat(ids).containsExactly(egyptian.getId().toString(), american.getId().toString());
        JsonNode eg = page.get("items").get(0);
        assertThat(eg.get("totalPaidCurrency").asText()).isEqualTo("EGP");
        assertThat(eg.get("totalPaid").decimalValue()).isEqualByComparingTo(egpAmount);
        assertThat(eg.get("totalPaidEgp").decimalValue()).isEqualByComparingTo(egpAmount);
        assertThat(eg.get("totalPaidBase").decimalValue()).isEqualByComparingTo(egpAmount);
        JsonNode us = page.get("items").get(1);
        assertThat(us.get("totalPaidCurrency").asText()).isEqualTo("USD");
        assertThat(us.get("totalPaid").decimalValue()).isEqualByComparingTo("30.00");
        assertThat(us.get("totalPaidEgp").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(us.get("totalPaidBase").decimalValue()).isEqualByComparingTo("100.00");

        // the 360 view says the same
        mvc.perform(get(ApiPaths.Admin.USERS + "/" + egyptian.getId()).header("Authorization", bearer(interpreter)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalPaidCurrency").value("EGP"))
                .andExpect(jsonPath("$.totalPaid").value(egpAmount.doubleValue()))
                .andExpect(jsonPath("$.totalPaidEgp").value(egpAmount.doubleValue()));
        mvc.perform(get(ApiPaths.Admin.USERS + "/" + american.getId()).header("Authorization", bearer(interpreter)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalPaidCurrency").value("USD"))
                .andExpect(jsonPath("$.totalPaid").value(30.0))
                .andExpect(jsonPath("$.totalPaidEgp").value(100.0));
        // a user who paid nothing is shown in his own currency too
        User none = createUser(tag + "-none", Role.USER);
        none.setCountryCode("EG");
        none = userRepository.save(none);
        mvc.perform(get(ApiPaths.Admin.USERS + "/" + none.getId()).header("Authorization", bearer(interpreter)))
                .andExpect(jsonPath("$.totalPaidCurrency").value("EGP"))
                .andExpect(jsonPath("$.totalPaid").value(0.0));
    }

    private JsonNode json(org.springframework.test.web.servlet.ResultActions actions) throws Exception {
        return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString());
    }
}
