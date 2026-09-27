package com.saadat.common.web;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Resolves the real client IP behind Cloudflare → Caddy → app.
 * Order: {@code CF-Connecting-IP}, first entry of {@code X-Forwarded-For}, {@code X-Real-IP}, remote address.
 */
public final class ClientIp {

    public static final String HEADER_CF_CONNECTING_IP = "CF-Connecting-IP";
    public static final String HEADER_X_FORWARDED_FOR = "X-Forwarded-For";
    public static final String HEADER_X_REAL_IP = "X-Real-IP";

    private static final int MAX_LENGTH = 64;

    private ClientIp() {
    }

    public static String resolve(HttpServletRequest request) {
        String ip = firstNonBlank(request.getHeader(HEADER_CF_CONNECTING_IP));
        if (ip == null) {
            String xff = request.getHeader(HEADER_X_FORWARDED_FOR);
            if (xff != null) {
                int comma = xff.indexOf(',');
                ip = firstNonBlank(comma >= 0 ? xff.substring(0, comma) : xff);
            }
        }
        if (ip == null) {
            ip = firstNonBlank(request.getHeader(HEADER_X_REAL_IP));
        }
        if (ip == null) {
            ip = request.getRemoteAddr();
        }
        if (ip == null) {
            return "unknown";
        }
        return ip.length() > MAX_LENGTH ? ip.substring(0, MAX_LENGTH) : ip;
    }

    private static String firstNonBlank(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
