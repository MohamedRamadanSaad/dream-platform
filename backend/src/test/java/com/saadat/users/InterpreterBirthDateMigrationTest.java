package com.saadat.users;

import static org.assertj.core.api.Assertions.assertThat;

import com.saadat.IntegrationTestBase;
import com.saadat.common.domain.Role;
import com.saadat.users.domain.User;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Profile data fix (docs/SESSIONS_PROFILE_CONTRACT.md §4): V19 sets the date of birth of the active interpreter
 * accounts. The test database is migrated while still empty, so the script is also run again, inside a transaction
 * that is rolled back, against accounts created here.
 */
class InterpreterBirthDateMigrationTest extends IntegrationTestBase {

    private static final String VERSION = "19";
    private static final String SCRIPT = "V19__interpreter_birth_date.sql";
    private static final LocalDate INTERPRETER_BIRTH_DATE = LocalDate.of(1988, 3, 6);

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Test
    void theMigrationIsApplied() {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select script, success from flyway_schema_history where version = ?", VERSION);
        assertThat(row.get("script")).isEqualTo(SCRIPT);
        assertThat(row.get("success")).isEqualTo(Boolean.TRUE);
    }

    @Test
    void theScriptSetsTheBirthDateOfActiveInterpretersOnly() throws Exception {
        User interpreter = createUser("birth-interpreter", Role.INTERPRETER);
        User deletedInterpreter = createUser("birth-deleted", Role.INTERPRETER);
        deletedInterpreter.setDeletedAt(Instant.now());
        userRepository.save(deletedInterpreter);
        User user = createUser("birth-user", Role.USER);
        String sql = new ClassPathResource("db/migration/" + SCRIPT).getContentAsString(StandardCharsets.UTF_8);

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> {
            jdbcTemplate.execute(sql);
            assertThat(birthDate(interpreter)).isEqualTo(INTERPRETER_BIRTH_DATE);
            assertThat(birthDate(deletedInterpreter)).isNull();
            assertThat(birthDate(user)).isNull();
            status.setRollbackOnly(); // leave the shared test database as it was
        });

        assertThat(birthDate(interpreter)).isNull();
    }

    private LocalDate birthDate(User user) {
        return jdbcTemplate.queryForObject("select birth_date from users where id = ?", LocalDate.class, user.getId());
    }
}
