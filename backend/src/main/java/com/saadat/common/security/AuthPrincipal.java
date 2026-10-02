package com.saadat.common.security;

import com.saadat.common.domain.Role;
import com.saadat.common.error.UnauthorizedException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The authenticated caller, decoded from the access token (no DB hit).
 * Set as the {@code principal} of the Spring Security {@link Authentication}, so controllers can use
 * either {@code AuthPrincipal.current()} or {@code @AuthenticationPrincipal AuthPrincipal me}.
 *
 * <p>{@code sessionId} is the token's {@code sid} claim: the refresh-token family (= signed-in device) the token was
 * issued for. It is null for access tokens issued before devices existed.
 */
public record AuthPrincipal(UUID userId, String email, Role role, UUID sessionId) {

    /** A caller without a known session (access token without {@code sid}). */
    public AuthPrincipal(UUID userId, String email, Role role) {
        this(userId, email, role, null);
    }

    public boolean isInterpreter() {
        return role == Role.INTERPRETER;
    }

    /** The current caller; throws 401 if the request is anonymous. */
    public static AuthPrincipal current() {
        return currentOptional().orElseThrow(UnauthorizedException::new);
    }

    /** The current caller if the request carries a valid access token (also on permitAll routes). */
    public static Optional<AuthPrincipal> currentOptional() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AuthPrincipal principal) {
            return Optional.of(principal);
        }
        return Optional.empty();
    }

    /** Never print the full e-mail in logs. */
    @Override
    public String toString() {
        return "AuthPrincipal[userId=" + userId + ", role=" + role + ", sessionId=" + sessionId + "]";
    }
}
