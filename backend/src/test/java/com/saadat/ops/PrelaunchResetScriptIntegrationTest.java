package com.saadat.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saadat.IntegrationTestBase;
import com.saadat.common.api.ApiPaths;
import com.saadat.common.domain.Role;
import com.saadat.users.domain.User;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * deploy/ops/prelaunch-reset.sql runs cleanly against the real schema, clears every payment and test dream, and keeps
 * the accounts and the visits. Runs inside a transaction that is rolled back (shared test database unchanged).
 */
class PrelaunchResetScriptIntegrationTest extends IntegrationTestBase {

    private static final String PACKAGE_ONE = "22222222-2222-4222-8222-000000000001";

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager txManager;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void clearsPaymentsAndTestDreamsAndKeepsAccountsAndVisits() throws Exception {
        String sql = Files.readAllLines(Path.of("..", "deploy", "ops", "prelaunch-reset.sql")).stream()
                .filter(line -> !line.trim().equalsIgnoreCase("BEGIN;") && !line.trim().equalsIgnoreCase("COMMIT;"))
                .collect(Collectors.joining("\n"));

        // a paid order (mock gateway in tests) and a dream it paid for, plus a visit
        User buyer = createUser("prelaunch-buyer", Role.USER);
        User interpreter = createUser("prelaunch-interpreter", Role.INTERPRETER);
        String draft = objectMapper.readTree(mvc.perform(post(ApiPaths.Dreams.ROOT).header("Authorization", bearer(buyer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("gender", "MALE",
                                "text", "رأيت أني أسقي شجرة صغيرة حتى كبرت وصار لها ظل واسع."))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asText();
        String orderId = objectMapper.readTree(mvc.perform(post(ApiPaths.Checkout.ROOT)
                        .header("Authorization", bearer(buyer)).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("packageId", PACKAGE_ONE, "dreamIds", List.of(draft)))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("orderId").asText();
        mvc.perform(post(ApiPaths.Webhooks.ROOT + "/mock/" + orderId).param("success", "true")).andExpect(status().isOk());
        String visitor = "prelaunch-visitor-" + UUID.randomUUID();
        mvc.perform(post(ApiPaths.Public.TRACK).contentType(MediaType.APPLICATION_JSON)
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                                + "Chrome/129.0 Safari/537.36")
                        .content(objectMapper.writeValueAsString(Map.of("path", "/", "sessionId", "prelaunch-visit-"
                                + UUID.randomUUID(), "visitorId", visitor))))
                .andExpect(status().is2xxSuccessful());

        assertThat(count("orders where id = '" + orderId + "'")).isEqualTo(1);
        assertThat(count("dreams where id = '" + draft + "' and status = 'IN_REVIEW'")).isEqualTo(1);
        long views = count("page_views");
        long users = count("users");
        long packages = count("packages");
        long settings = count("app_settings");

        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.executeWithoutResult(status -> {
            jdbc.execute(sql);
            assertThat(count("orders")).isZero();
            assertThat(count("credit_ledger")).isZero();
            assertThat(count("coupon_redemptions")).isZero();
            assertThat(count("dreams")).isZero();
            assertThat(count("notifications")).isZero();
            assertThat(count("email_log")).isZero();
            assertThat(count("audit_log")).isZero();
            assertThat(count("promotions where used_count <> 0")).isZero();
            // kept
            assertThat(count("users")).isEqualTo(users);
            assertThat(count("users where id = '" + buyer.getId() + "'")).isEqualTo(1);
            assertThat(count("users where id = '" + interpreter.getId() + "'")).isEqualTo(1);
            assertThat(count("page_views")).isEqualTo(views).isPositive();
            assertThat(count("page_views where visitor_id = '" + visitor + "'")).isEqualTo(1);
            assertThat(count("packages")).isEqualTo(packages).isPositive();
            assertThat(count("app_settings")).isEqualTo(settings).isPositive();
            status.setRollbackOnly();
        });

        assertThat(count("orders where id = '" + orderId + "'")).as("rolled back").isEqualTo(1);
    }

    private long count(String tableAndWhere) {
        Long n = jdbc.queryForObject("select count(*) from " + tableAndWhere, Long.class);
        return n == null ? 0 : n;
    }
}
