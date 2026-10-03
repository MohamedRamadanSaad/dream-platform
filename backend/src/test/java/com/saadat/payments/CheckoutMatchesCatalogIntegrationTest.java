package com.saadat.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.saadat.IntegrationTestBase;
import com.saadat.common.api.ApiPaths;
import com.saadat.common.domain.Role;
import com.saadat.payments.domain.Order;
import com.saadat.payments.repo.OrderRepository;
import com.saadat.users.domain.User;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * What a signed-in user sees on the packages page is exactly what the order charges: same amount, same currency
 * (EGP in Egypt, USD everywhere else), for every active package. A checkout whose price after discounts would be
 * below 1 is refused before any order or provider call.
 */
class CheckoutMatchesCatalogIntegrationTest extends IntegrationTestBase {

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    OrderRepository orderRepository;

    @Test
    void theOrderChargesExactlyTheCatalogPriceInTheCountryCurrency() throws Exception {
        for (String country : List.of("EG", "SA", "AE", "DE", "US", "NG")) {
            User user = userIn(country);
            JsonNode catalog = json(mvc.perform(get(ApiPaths.Public.CATALOG)
                            .header(HttpHeaders.AUTHORIZATION, bearer(user)))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            String expected = "EG".equals(country) ? "EGP" : "USD";
            assertThat(catalog.get("currency").asText()).as(country).isEqualTo(expected);
            assertThat(catalog.get("packages").size()).as(country).isPositive();

            for (JsonNode pkg : catalog.get("packages")) {
                JsonNode checkout = json(mvc.perform(post(ApiPaths.Checkout.ROOT)
                                .header(HttpHeaders.AUTHORIZATION, bearer(user))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(Map.of("packageId", pkg.get("id").asText()))))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
                String label = country + " " + pkg.get("name").asText();
                assertThat(checkout.get("currency").asText()).as(label).isEqualTo(expected);
                assertThat(new BigDecimal(checkout.get("amount").asText())).as(label)
                        .isEqualByComparingTo(new BigDecimal(pkg.get("price").asText()));

                Order order = orderRepository.findById(UUID.fromString(checkout.get("orderId").asText())).orElseThrow();
                assertThat(order.getCurrency().name()).as(label).isEqualTo(expected);
                assertThat(order.getAmount()).as(label).isEqualByComparingTo(new BigDecimal(pkg.get("price").asText()));
                assertThat(order.getCountryCode()).as(label).isEqualTo(country);
                assertThat(order.getCredits()).as(label).isEqualTo(pkg.get("credits").asInt());
            }
        }
    }

    @Test
    void aDiscountThatLeavesNothingToPayIsRefused() throws Exception {
        User interpreter = createUser("guard-admin", Role.INTERPRETER);
        String code = "ZERO" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        mvc.perform(post(ApiPaths.Admin.COUPONS).header(HttpHeaders.AUTHORIZATION, bearer(interpreter))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("code", code, "type", "FIXED", "value", 100000,
                                "perUserLimit", 1, "active", true))))
                .andExpect(status().is2xxSuccessful());
        User user = userIn("SA");
        long ordersBefore = orderRepository.count();
        mvc.perform(post(ApiPaths.Checkout.ROOT).header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "packageId", "22222222-2222-4222-8222-000000000001", "couponCode", code))))
                .andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.code").value("AMOUNT_TOO_LOW"));
        assertThat(orderRepository.count()).isEqualTo(ordersBefore);
    }

    private User userIn(String country) {
        User user = createUser("pay-" + country.toLowerCase(), Role.USER);
        user.setCountryCode(country);
        return userRepository.save(user);
    }

    private JsonNode json(String body) throws Exception {
        return objectMapper.readTree(body);
    }
}
