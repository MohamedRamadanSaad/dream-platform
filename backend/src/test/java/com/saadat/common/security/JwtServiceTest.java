package com.saadat.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.saadat.common.domain.Role;
import com.saadat.config.props.AppProperties;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JwtServiceTest {

    private static final String SECRET = "unit-test-secret-0123456789abcdef-0123456789";
    private static final Instant T0 = Instant.parse("2026-01-01T10:00:00Z");

    private static AppProperties props(String secret, String issuer) {
        AppProperties p = new AppProperties();
        p.getAuth().setJwtSecret(secret);
        p.getAuth().setJwtIssuer(issuer);
        return p;
    }

    private static JwtService service(Instant now) {
        return new JwtService(props(SECRET, "saadat-api"), Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void issueAndVerifyRoundTrip() {
        JwtService jwt = service(T0);
        UUID userId = UUID.randomUUID();

        String token = jwt.issue(userId, Role.INTERPRETER, "fatema@example.com", 15);
        AuthPrincipal principal = jwt.verify(token);

        assertThat(principal.userId()).isEqualTo(userId);
        assertThat(principal.role()).isEqualTo(Role.INTERPRETER);
        assertThat(principal.email()).isEqualTo("fatema@example.com");
        assertThat(principal.isInterpreter()).isTrue();
        assertThat(JWT.decode(token).getSubject()).isEqualTo(userId.toString());
        assertThat(JWT.decode(token).getExpiresAtAsInstant()).isEqualTo(T0.plus(Duration.ofMinutes(15)));
    }

    @Test
    void sessionIdRoundTripsAsSid() {
        JwtService jwt = service(T0);
        UUID sessionId = UUID.randomUUID();

        String token = jwt.issue(UUID.randomUUID(), Role.USER, "a@b.c", 15, sessionId);

        assertThat(jwt.verify(token).sessionId()).isEqualTo(sessionId);
        assertThat(JWT.decode(token).getClaim(JwtService.CLAIM_SESSION_ID).asString()).isEqualTo(sessionId.toString());
        // tokens issued without a session carry no sid
        String old = jwt.issue(UUID.randomUUID(), Role.USER, "a@b.c", 15);
        assertThat(jwt.verify(old).sessionId()).isNull();
        assertThat(JWT.decode(old).getClaim(JwtService.CLAIM_SESSION_ID).asString()).isNull();
    }

    @Test
    void malformedSidIsRejected() {
        String token = JWT.create()
                .withIssuer("saadat-api")
                .withSubject(UUID.randomUUID().toString())
                .withClaim(JwtService.CLAIM_ROLE, "USER")
                .withClaim(JwtService.CLAIM_SESSION_ID, "not-a-uuid")
                .withIssuedAt(T0)
                .withExpiresAt(T0.plus(Duration.ofMinutes(15)))
                .sign(Algorithm.HMAC256(SECRET.getBytes(StandardCharsets.UTF_8)));

        assertThatThrownBy(() -> service(T0).verify(token)).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void expiredTokenIsRejected() {
        String token = service(T0).issue(UUID.randomUUID(), Role.USER, "a@b.c", 15);

        JwtService later = service(T0.plus(Duration.ofMinutes(16)));

        assertThatThrownBy(() -> later.verify(token)).isInstanceOf(InvalidTokenException.class);
        assertThat(later.tryVerify(token)).isEmpty();
    }

    @Test
    void tokenStillValidJustBeforeExpiry() {
        String token = service(T0).issue(UUID.randomUUID(), Role.USER, "a@b.c", 15);

        assertThat(service(T0.plus(Duration.ofMinutes(14))).tryVerify(token)).isPresent();
    }

    @Test
    void tokenSignedWithAnotherSecretIsRejected() {
        JwtService other = new JwtService(props("another-secret-0123456789abcdef-0123456789", "saadat-api"),
                Clock.fixed(T0, ZoneOffset.UTC));
        String token = other.issue(UUID.randomUUID(), Role.USER, "a@b.c", 15);

        assertThatThrownBy(() -> service(T0).verify(token)).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void tamperedPayloadIsRejected() {
        JwtService jwt = service(T0);
        String token = jwt.issue(UUID.randomUUID(), Role.USER, "a@b.c", 15);
        // forge a payload claiming INTERPRETER but keep the original signature
        String[] parts = token.split("\\.");
        String forgedPayload = JWT.create()
                .withIssuer("saadat-api")
                .withSubject(UUID.randomUUID().toString())
                .withClaim(JwtService.CLAIM_ROLE, "INTERPRETER")
                .withExpiresAt(T0.plus(Duration.ofMinutes(15)))
                .sign(Algorithm.none())
                .split("\\.")[1];
        String forged = parts[0] + "." + forgedPayload + "." + parts[2];

        assertThatThrownBy(() -> jwt.verify(forged)).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void wrongIssuerIsRejected() {
        String token = JWT.create()
                .withIssuer("someone-else")
                .withSubject(UUID.randomUUID().toString())
                .withClaim(JwtService.CLAIM_ROLE, "USER")
                .withIssuedAt(T0)
                .withExpiresAt(T0.plus(Duration.ofMinutes(15)))
                .sign(Algorithm.HMAC256(SECRET.getBytes(StandardCharsets.UTF_8)));

        assertThatThrownBy(() -> service(T0).verify(token)).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void unknownRoleIsRejected() {
        String token = JWT.create()
                .withIssuer("saadat-api")
                .withSubject(UUID.randomUUID().toString())
                .withClaim(JwtService.CLAIM_ROLE, "ADMIN")
                .withIssuedAt(T0)
                .withExpiresAt(T0.plus(Duration.ofMinutes(15)))
                .sign(Algorithm.HMAC256(SECRET.getBytes(StandardCharsets.UTF_8)));

        assertThatThrownBy(() -> service(T0).verify(token)).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void garbageAndBlankTokensAreRejected() {
        JwtService jwt = service(T0);
        assertThat(jwt.tryVerify("not-a-jwt")).isEmpty();
        assertThat(jwt.tryVerify("")).isEmpty();
        assertThat(jwt.tryVerify(null)).isEmpty();
    }

    @Test
    void shortSecretFailsFast() {
        assertThatThrownBy(() -> new JwtService(props("too-short", "saadat-api"), Clock.systemUTC()))
                .isInstanceOf(IllegalStateException.class);
    }
}
