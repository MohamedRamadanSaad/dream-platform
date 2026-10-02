package com.saadat.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import com.saadat.common.domain.Role;
import java.util.List;
import java.util.Optional;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Reads {@code Authorization: Bearer <jwt>}; when valid AND the account still exists, puts an {@link AuthPrincipal}
 * with authority {@code ROLE_<role>} into the security context. The role is the one stored in the database now
 * ({@link AccountRoleLookup}), never the role written in the token, so revoking a role or deleting an account takes
 * effect on the next request. Invalid/missing tokens and deleted accounts leave the request anonymous — the
 * authorization rules in SecurityConfig then decide (401 for protected routes).
 *
 * <p>Not a Spring bean on purpose (would otherwise be auto-registered as a servlet filter as well);
 * it is instantiated by SecurityConfig.
 */
public class JwtAuthFilter extends OncePerRequestFilter {

    public static final String ROLE_PREFIX = "ROLE_";
    public static final String MDC_USER_ID = "userId";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final AccountRoleLookup accountRoleLookup;

    public JwtAuthFilter(JwtService jwtService, AccountRoleLookup accountRoleLookup) {
        this.jwtService = jwtService;
        this.accountRoleLookup = accountRoleLookup;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        boolean authenticated = false;
        if (header != null && header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            String token = header.substring(BEARER_PREFIX.length()).trim();
            Optional<AuthPrincipal> verified = jwtService.tryVerify(token);
            Optional<Role> currentRole = verified.flatMap(v -> accountRoleLookup.currentRole(v.userId()));
            if (verified.isPresent() && currentRole.isPresent()) {
                AuthPrincipal p = new AuthPrincipal(verified.get().userId(), verified.get().email(), currentRole.get());
                UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                        p, null, List.of(new SimpleGrantedAuthority(ROLE_PREFIX + p.role().name())));
                SecurityContext context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(authentication);
                SecurityContextHolder.setContext(context);
                MDC.put(MDC_USER_ID, p.userId().toString());
                authenticated = true;
            }
        }
        try {
            chain.doFilter(request, response);
        } finally {
            if (authenticated) {
                MDC.remove(MDC_USER_ID);
            }
        }
    }
}
