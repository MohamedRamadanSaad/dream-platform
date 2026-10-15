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
import org.springframework.test.web.servlet.MvcResult;

/** Readable order numbers (V38): RN-<n>, increasing and unique, shown as {@code orderNumber}; admin search by it. */
class OrderNumberIntegrationTest extends IntegrationTestBase {

    /** Seeded package p1 (1 credit). */
    private static final String PACKAGE_ONE = "22222222-2222-4222-8222-000000000001";

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    OrderRepository orderRepository;

    @Test
    void newOrdersGetIncreasingUniqueNumbersShownToTheBuyer() throws Exception {
        User user = createUser("order-number", Role.USER);
        List<String> numbers = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            UUID orderId = checkout(user);
            String number = orderRepository.findById(orderId).orElseThrow().getOrderNumber();
            assertThat(number).matches("RN-\\d{5,}");
            numbers.add(number);

            mvc.perform(get(ApiPaths.Me.ORDERS + "/" + orderId).header("Authorization", bearer(user)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(orderId.toString()))
                    .andExpect(jsonPath("$.orderNumber").value(number));
        }
        assertThat(numbers).doesNotHaveDuplicates();
        assertThat(numbers.stream().mapToLong(OrderNumberIntegrationTest::digits).toArray()).isSorted();
        assertThat(digits(numbers.get(0))).isGreaterThanOrEqualTo(15001L);

        JsonNode mine = objectMapper.readTree(mvc.perform(get(ApiPaths.Me.ORDERS).header("Authorization", bearer(user)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        List<String> listed = new ArrayList<>();
        mine.get("items").forEach(o -> listed.add(o.get("orderNumber").asText()));
        assertThat(listed).containsExactlyInAnyOrderElementsOf(numbers);
    }

    @Test
    void aRowInsertedWithoutANumberGetsOneFromTheColumnDefault() {
        User user = createUser("order-number-default", Role.USER);
        Order o = new Order();
        o.setUserId(user.getId());
        o.setPackageNameSnapshot("Fixture");
        o.setCredits(1);
        o.setAmount(BigDecimal.TEN);
        o.setCurrency(Currency.SAR);
        o.setProvider(PaymentProviderType.MOCK);
        o.setStatus(OrderStatus.FAILED);
        o.setExpiresAt(Instant.now().plus(Duration.ofHours(1)));
        UUID id = orderRepository.saveAndFlush(o).getId();
        assertThat(orderRepository.findById(id).orElseThrow().getOrderNumber()).startsWith("RN-");
    }

    @Test
    void adminFindsAnOrderByItsNumberWithOrWithoutThePrefix() throws Exception {
        User buyer = createUser("order-number-search", Role.USER);
        User interpreter = createUser("order-number-admin", Role.INTERPRETER);
        UUID orderId = checkout(buyer);
        String number = orderRepository.findById(orderId).orElseThrow().getOrderNumber();
        String digits = String.valueOf(digits(number));

        for (String q : new String[] {number, number.toLowerCase(), digits}) {
            mvc.perform(get(ApiPaths.Admin.ORDERS).header("Authorization", bearer(interpreter)).param("q", q))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(1))
                    .andExpect(jsonPath("$.items[0].id").value(orderId.toString()))
                    .andExpect(jsonPath("$.items[0].orderNumber").value(number))
                    .andExpect(jsonPath("$.items[0].userName").value("order-number-search"));
        }
        mvc.perform(get(ApiPaths.Admin.ORDERS).header("Authorization", bearer(interpreter)).param("q", "not a number"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void searchTextIsParsedLeniently() {
        assertThat(OrderNumbers.parseQuery("rn-15001")).hasValue(new OrderNumbers.Query("RN-15001", "15001"));
        assertThat(OrderNumbers.parseQuery(" #RN 15001 ")).hasValue(new OrderNumbers.Query("RN-15001", "15001"));
        assertThat(OrderNumbers.parseQuery("15001")).hasValue(new OrderNumbers.Query("15001", "15001"));
        assertThat(OrderNumbers.parseQuery("15001").orElseThrow().hasPrefix()).isFalse();
        assertThat(OrderNumbers.parseQuery("abc")).isEmpty();
        assertThat(OrderNumbers.parseQuery("")).isEmpty();
    }

    private UUID checkout(User user) throws Exception {
        MvcResult r = mvc.perform(post(ApiPaths.Checkout.ROOT).header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("packageId", PACKAGE_ONE))))
                .andExpect(status().isOk())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(r.getResponse().getContentAsString()).get("orderId").asText());
    }

    private static long digits(String number) {
        return Long.parseLong(number.substring(number.lastIndexOf('-') + 1));
    }
}
