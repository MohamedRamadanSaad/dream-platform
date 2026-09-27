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
 * SameSite, scoped to the /api/auth path.
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

    public void writeRefreshToken(HttpServletResponse response, String rawToken, Duration maxAge) {
        response.addHeader(HttpHeaders.SET_COOKIE, build(rawToken, maxAge).toString());
    }

    public void clearRefreshToken(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, build("", Duration.ZERO).toString());
    }

    private ResponseCookie build(String value, Duration maxAge) {
        return ResponseCookie.from(config.getRefreshCookieName(), value)
                .httpOnly(true)
                .secure(config.isRefreshCookieSecure())
                .sameSite(config.getRefreshCookieSameSite())
                .path(config.getRefreshCookiePath())
                .maxAge(maxAge)
                .build();
    }
}
