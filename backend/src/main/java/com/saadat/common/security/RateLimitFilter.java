package com.saadat.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saadat.common.api.ApiPaths;
import com.saadat.common.error.ProblemWriter;
import com.saadat.common.error.Problems;
import com.saadat.common.web.ClientIp;
import com.saadat.common.web.RequestPaths;
import com.saadat.config.props.AppProperties;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * In-memory Bucket4j rate limiting (spec §3):
 * <ul>
 *   <li>{@code /auth/**}: {@code app.rate-limit.auth-per-minute} per IP</li>
 *   <li>{@code POST /dreams}: {@code app.rate-limit.dreams-per-minute} per user (IP when anonymous)</li>
 *   <li>{@code /checkout}: {@code app.rate-limit.checkout-per-minute} per IP</li>
 *   <li>{@code POST /public/track}: {@code app.rate-limit.track-per-minute} per IP</li>
 * </ul>
 * Runs after {@link JwtAuthFilter} so the user id is available. Instantiated by SecurityConfig (not a bean).
 * Single-instance only: with several replicas, move buckets to a shared store.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final AppProperties.RateLimit config;
    private final ObjectMapper objectMapper;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimitFilter(AppProperties properties, ObjectMapper objectMapper) {
        this.config = properties.getRateLimit();
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!config.isEnabled() || HttpMethod.OPTIONS.matches(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }
        String path = RequestPaths.withinApplication(request);
        String key = null;
        int capacity = 0;

        if (path.startsWith(ApiPaths.Auth.ROOT + "/")) {
            key = "auth:" + ClientIp.resolve(request);
            capacity = config.getAuthPerMinute();
        } else if (path.equals(ApiPaths.Checkout.ROOT)) {
            key = "checkout:" + ClientIp.resolve(request);
            capacity = config.getCheckoutPerMinute();
        } else if (path.equals(ApiPaths.Dreams.ROOT) && HttpMethod.POST.matches(request.getMethod())) {
            String who = AuthPrincipal.currentOptional()
                    .map(p -> "user:" + p.userId())
                    .orElseGet(() -> "ip:" + ClientIp.resolve(request));
            key = "dreams:" + who;
            capacity = config.getDreamsPerMinute();
        } else if (path.equals(ApiPaths.Public.TRACK) && HttpMethod.POST.matches(request.getMethod())) {
            key = "track:" + ClientIp.resolve(request);
            capacity = config.getTrackPerMinute();
        }

        if (key == null) {
            chain.doFilter(request, response);
            return;
        }

        if (buckets.size() > config.getMaxKeys()) {
            buckets.clear();
        }
        final int bucketCapacity = capacity;
        Bucket bucket = buckets.computeIfAbsent(key, k -> newBucket(bucketCapacity));
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            chain.doFilter(request, response);
            return;
        }

        long retryAfterSeconds = Math.max(1, TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill()));
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds));
        ProblemDetail problem = Problems.of(HttpStatus.TOO_MANY_REQUESTS, "too-many-requests", null,
                "Too many requests, retry in " + retryAfterSeconds + "s");
        problem.setProperty(Problems.PROP_CODE, "RATE_LIMITED");
        ProblemWriter.write(request, response, objectMapper, problem);
    }

    private static Bucket newBucket(int capacity) {
        Bandwidth limit = Bandwidth.builder()
                .capacity(capacity)
                .refillGreedy(capacity, WINDOW)
                .build();
        return Bucket.builder().addLimit(limit).build();
    }
}
