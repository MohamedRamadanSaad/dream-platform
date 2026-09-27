package com.saadat.common.security;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.saadat.common.domain.Role;
import com.saadat.config.props.AppProperties;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Issues and verifies HS256 access tokens: {@code sub}=userId, {@code role}, {@code email}, {@code iss}, {@code iat},
 * {@code exp}, {@code jti}. The TTL is passed by the caller (setting {@code auth.access_ttl_minutes}).
 */
@Service
public class JwtService {

    public static final String CLAIM_ROLE = "role";
    public static final String CLAIM_EMAIL = "email";

    /** Minimum secret length for HS256 (256 bits). */
    public static final int MIN_SECRET_LENGTH = 32;

    private static final long LEEWAY_SECONDS = 5;

    private final Algorithm algorithm;
    private final String issuer;
    private final Clock clock;
    private final JWTVerifier verifier;

    public JwtService(AppProperties properties, Clock clock) {
        String secret = properties.getAuth().getJwtSecret();
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_LENGTH) {
            throw new IllegalStateException("app.auth.jwt-secret must be at least " + MIN_SECRET_LENGTH + " bytes");
        }
        this.algorithm = Algorithm.HMAC256(secret.getBytes(StandardCharsets.UTF_8));
        this.issuer = properties.getAuth().getJwtIssuer();
        this.clock = clock;
        JWTVerifier.BaseVerification verification = (JWTVerifier.BaseVerification) JWT.require(algorithm)
                .withIssuer(issuer)
                .acceptLeeway(LEEWAY_SECONDS);
        this.verifier = verification.build(clock);
    }

    /** Issues an access token valid for {@code ttlMinutes}. */
    public String issue(UUID userId, Role role, String email, long ttlMinutes) {
        return issue(userId, role, email, Duration.ofMinutes(ttlMinutes));
    }

    public String issue(UUID userId, Role role, String email, Duration ttl) {
        Instant now = clock.instant();
        return JWT.create()
                .withIssuer(issuer)
                .withSubject(userId.toString())
                .withClaim(CLAIM_ROLE, role.name())
                .withClaim(CLAIM_EMAIL, email)
                .withIssuedAt(now)
                .withExpiresAt(now.plus(ttl))
                .withJWTId(UUID.randomUUID().toString())
                .sign(algorithm);
    }

    /** Verifies signature, issuer and expiry; throws {@link InvalidTokenException} on any problem. */
    public AuthPrincipal verify(String token) {
        if (token == null || token.isBlank()) {
            throw new InvalidTokenException("Empty token");
        }
        final DecodedJWT jwt;
        try {
            jwt = verifier.verify(token);
        } catch (JWTVerificationException e) {
            throw new InvalidTokenException("Invalid access token", e);
        }
        String subject = jwt.getSubject();
        String roleClaim = jwt.getClaim(CLAIM_ROLE).asString();
        String email = jwt.getClaim(CLAIM_EMAIL).asString();
        if (subject == null || roleClaim == null) {
            throw new InvalidTokenException("Missing claims");
        }
        try {
            return new AuthPrincipal(UUID.fromString(subject), email, Role.valueOf(roleClaim));
        } catch (IllegalArgumentException e) {
            throw new InvalidTokenException("Malformed claims", e);
        }
    }

    /** Like {@link #verify(String)} but returns empty instead of throwing. */
    public Optional<AuthPrincipal> tryVerify(String token) {
        try {
            return Optional.of(verify(token));
        } catch (InvalidTokenException e) {
            return Optional.empty();
        }
    }
}
