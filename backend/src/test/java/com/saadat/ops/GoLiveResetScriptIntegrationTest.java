package com.saadat.ops;

import static org.assertj.core.api.Assertions.assertThat;

import com.saadat.IntegrationTestBase;
import com.saadat.common.domain.Role;
import com.saadat.users.domain.User;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * deploy/ops/go-live-reset.sql runs cleanly against the real schema (every table that references a cleared table is
 * in the TRUNCATE list) and keeps the fixed data. Runs inside a transaction that is rolled back, so the shared test
 * database is unchanged for the other tests.
 */
class GoLiveResetScriptIntegrationTest extends IntegrationTestBase {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager txManager;

    @Test
    void clearsTestActivityAndKeepsFixedData() throws Exception {
        Path script = Path.of("..", "deploy", "ops", "go-live-reset.sql");
        String sql = Files.readAllLines(script).stream()
                .filter(line -> !line.trim().equalsIgnoreCase("BEGIN;") && !line.trim().equalsIgnoreCase("COMMIT;"))
                .collect(Collectors.joining("\n"));

        User visitor = createUser("reset-visitor", Role.USER);
        User interpreter = createUser("reset-interpreter", Role.INTERPRETER);
        User support = createUser("reset-support", Role.INTERPRETER);
        String supportEmail = "support@saadatu-aldarein.com";
        jdbc.update("update users set email = ? where id = ? and not exists (select 1 from users where email = ?)",
                supportEmail, support.getId(), supportEmail);
        long countries = count("countries");
        long packages = count("packages");
        long priceRules = count("price_rules");
        long settings = count("app_settings");

        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.executeWithoutResult(status -> {
            jdbc.execute(sql);
            assertThat(exists(visitor.getId())).as("visitor removed").isFalse();
            assertThat(exists(interpreter.getId())).as("interpreter kept").isTrue();
            assertThat(count("users where role = 'USER'")).isZero();
            assertThat(count("dreams")).isZero();
            assertThat(count("orders")).isZero();
            assertThat(count("credit_ledger")).isZero();
            assertThat(count("page_views")).isZero();
            assertThat(count("refresh_tokens")).isZero();
            assertThat(count("countries")).isEqualTo(countries).isPositive();
            assertThat(count("packages")).isEqualTo(packages).isPositive();
            assertThat(count("price_rules")).isEqualTo(priceRules).isPositive();
            assertThat(count("app_settings")).isEqualTo(settings).isPositive();
            assertThat(count("promotions where used_count <> 0")).isZero();
            assertThat(jdbc.queryForObject("select country_code from users where email = ?", String.class,
                    supportEmail)).isEqualTo("EG");
            status.setRollbackOnly();
        });

        assertThat(exists(visitor.getId())).as("rolled back").isTrue();
    }

    private long count(String tableAndWhere) {
        Long n = jdbc.queryForObject("select count(*) from " + tableAndWhere, Long.class);
        return n == null ? 0 : n;
    }

    private boolean exists(UUID userId) {
        return count("users where id = '" + userId + "'") > 0;
    }
}
