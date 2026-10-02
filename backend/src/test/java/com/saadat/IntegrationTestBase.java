package com.saadat;

import com.saadat.common.domain.Role;
import com.saadat.common.security.JwtService;
import com.saadat.users.domain.User;
import com.saadat.users.repo.UserRepository;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Base class for integration tests: full Spring context, profile {@code test}, a real PostgreSQL 16
 * (Testcontainers) migrated by Flyway, and MockMvc.
 *
 * <p>The container is a JVM-wide singleton started once (not {@code @Container}), so the cached Spring
 * context stays valid across test classes; Ryuk removes it when the JVM exits.
 *
 * <p>MockMvc requests do NOT include the {@code /api} context path: use {@code mvc.perform(get(ApiPaths.X))}.
 * Helpers: {@link #createUser(String, Role)} and {@link #bearer(User)} for an {@code Authorization} header.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
public abstract class IntegrationTestBase {

    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("saadat")
            .withUsername("saadat")
            .withPassword("saadat");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected JwtService jwtService;

    @Autowired
    protected UserRepository userRepository;

    /** Persists an onboarded user with a unique e-mail derived from {@code emailPrefix}. */
    protected User createUser(String emailPrefix, Role role) {
        User user = new User();
        user.setEmail(emailPrefix + "-" + UUID.randomUUID() + "@example.com");
        user.setName(emailPrefix);
        user.setRole(role);
        user.setOnboarded(true);
        user.setCountryCode("SA");
        return userRepository.save(user);
    }

    /** {@code "Bearer <jwt>"} for the given user (15-minute token). */
    protected String bearer(User user) {
        return bearer(user.getId(), user.getRole(), user.getEmail());
    }

    protected String bearer(UUID userId, Role role, String email) {
        return "Bearer " + jwtService.issue(userId, role, email, 15);
    }

    /** Polls {@code condition} (e.g. an e-mail written by the async pool) for up to 15 seconds. */
    protected static void awaitTrue(String what, BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 15_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("Timed out waiting for: " + what);
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for: " + what, e);
            }
        }
    }
}
