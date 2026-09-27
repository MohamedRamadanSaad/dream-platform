package com.saadat.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saadat.common.api.ApiPaths;
import com.saadat.common.domain.Role;
import com.saadat.common.error.ProblemWriter;
import com.saadat.common.error.Problems;
import com.saadat.common.web.RequestIdFilter;
import com.saadat.config.props.AppProperties;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Stateless JWT security (spec §3). All paths come from {@link ApiPaths}; feature code must not need to
 * touch this class:
 * <ul>
 *   <li>open: /auth/** (except /auth/onboarding), /public/**, /webhooks/**, health, OpenAPI docs, /error</li>
 *   <li>/admin/** → ROLE_INTERPRETER</li>
 *   <li>everything else → authenticated</li>
 * </ul>
 * 401/403 are rendered as RFC 7807 problem JSON.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private static final List<String> ALLOWED_METHODS = List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");
    private static final long CORS_MAX_AGE_SECONDS = 3600L;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtService jwtService,
                                                   AppProperties properties,
                                                   ObjectMapper objectMapper) throws Exception {
        JwtAuthFilter jwtAuthFilter = new JwtAuthFilter(jwtService);
        RateLimitFilter rateLimitFilter = new RateLimitFilter(properties, objectMapper);

        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource(properties)))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(authenticationEntryPoint(objectMapper))
                        .accessDeniedHandler(accessDeniedHandler(objectMapper)))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        // the only /auth route that needs a token
                        .requestMatchers(ApiPaths.Auth.ONBOARDING).authenticated()
                        .requestMatchers(ApiPaths.Auth.ALL).permitAll()
                        .requestMatchers(ApiPaths.Public.ALL).permitAll()
                        .requestMatchers(ApiPaths.Webhooks.ALL).permitAll()
                        .requestMatchers(
                                ApiPaths.Infra.ERROR,
                                ApiPaths.Infra.HEALTH,
                                ApiPaths.Infra.HEALTH_ALL,
                                ApiPaths.Infra.INFO,
                                ApiPaths.Infra.OPENAPI,
                                ApiPaths.Infra.SWAGGER_UI,
                                ApiPaths.Infra.SWAGGER_UI_HTML).permitAll()
                        .requestMatchers(ApiPaths.Admin.ALL).hasRole(Role.INTERPRETER.name())
                        .anyRequest().authenticated())
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(rateLimitFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    static CorsConfigurationSource corsConfigurationSource(AppProperties properties) {
        Set<String> origins = new LinkedHashSet<>();
        addOrigin(origins, properties.getFrontendUrl());
        for (String origin : properties.getCors().getAllowedOrigins()) {
            addOrigin(origins, origin);
        }

        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(new ArrayList<>(origins));
        config.setAllowedMethods(ALLOWED_METHODS);
        config.setAllowedHeaders(List.of("*"));
        config.setExposedHeaders(List.of(RequestIdFilter.HEADER, HttpHeaders.RETRY_AFTER, HttpHeaders.LOCATION));
        config.setAllowCredentials(true); // refresh-token cookie
        config.setMaxAge(CORS_MAX_AGE_SECONDS);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    private static void addOrigin(Set<String> origins, String origin) {
        if (origin == null) {
            return;
        }
        String trimmed = origin.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (!trimmed.isEmpty()) {
            origins.add(trimmed);
        }
    }

    private static AuthenticationEntryPoint authenticationEntryPoint(ObjectMapper objectMapper) {
        return (request, response, authException) -> ProblemWriter.write(request, response, objectMapper,
                Problems.of(HttpStatus.UNAUTHORIZED, "unauthenticated", null, "Authentication required"));
    }

    private static AccessDeniedHandler accessDeniedHandler(ObjectMapper objectMapper) {
        return (request, response, accessDeniedException) -> ProblemWriter.write(request, response, objectMapper,
                Problems.of(HttpStatus.FORBIDDEN, "forbidden", null, "Access denied"));
    }
}
