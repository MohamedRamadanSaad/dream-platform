package com.saadat.common.web;

import jakarta.servlet.http.HttpServletRequest;

/** Path helpers for filters (which see the full URI including the /api context path). */
public final class RequestPaths {

    private RequestPaths() {
    }

    /** Request path without the context path and without a trailing slash, e.g. "/dreams". */
    public static String withinApplication(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        String path = contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)
                ? uri.substring(contextPath.length())
                : uri;
        if (path.isEmpty()) {
            return "/";
        }
        if (path.length() > 1 && path.endsWith("/")) {
            return path.substring(0, path.length() - 1);
        }
        return path;
    }
}
