package com.saadat.auth.web;

import com.saadat.config.props.AppProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * The refresh-token cookie ({@code app.auth.refresh-cookie-*}): HttpOnly, Secure (except local),
 * SameSite, scoped to the /api/auth path. "Remember me" sign-ins get a persistent cookie (Max-Age = the family's
 * lifetime); the others a browser-session cookie (no Max-Age / Expires) that the browser drops when it closes.
 */
@Component
public class CookieUtil {

    private final AppProperties.Auth config;

    public CookieUtil(AppProperties properties) {
        this.config = properties.getAuth();
    }

    public String readRefreshToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (config.getRefreshCookieName().equals(cookie.getName())) {
                String value = cookie.getValue();
                return value == null || value.isBlank() ? null : value;
            }
        }
        return null;
    }

    /**
     * Writes the refresh token: {@code persistent} = cookie kept for {@code ttl} (Max-Age); otherwise a
     * browser-session cookie without Max-Age.
     */
    public void writeRefreshToken(HttpServletResponse response, String rawToken, Duration ttl, boolean persistent) {
        ResponseCookie.ResponseCookieBuilder cookie = builder(rawToken);
        if (persistent) {
            cookie.maxAge(ttl);
        }
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.build().toString());
    }

    public void clearRefreshToken(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, builder("").maxAge(Duration.ZERO).build().toString());
    }

    private ResponseCookie.ResponseCookieBuilder builder(String value) {
        return ResponseCookie.from(config.getRefreshCookieName(), value)
                .httpOnly(true)
                .secure(config.isRefreshCookieSecure())
                .sameSite(config.getRefreshCookieSameSite())
                .path(config.getRefreshCookiePath());
    }
}
