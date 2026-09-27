package com.saadat.common.error;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * Base domain exception rendered as RFC 7807 by {@link ApiExceptionHandler}.
 *
 * <ul>
 *   <li>{@code status} → HTTP status</li>
 *   <li>{@code slug} → {@code type = https://api.saadatu-aldarein.com/errors/<slug>}</li>
 *   <li>{@code code} → optional machine code property (e.g. INSUFFICIENT_CREDITS)</li>
 *   <li>{@code getMessage()} → problem {@code detail}</li>
 * </ul>
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String slug;
    private final String title;
    private final String code;
    private final Map<String, Object> properties = new LinkedHashMap<>();

    public ApiException(HttpStatus status, String slug, String code, String detail) {
        this(status, slug, null, code, detail);
    }

    public ApiException(HttpStatus status, String slug, String title, String code, String detail) {
        super(detail);
        this.status = status;
        this.slug = slug;
        this.title = title;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getSlug() {
        return slug;
    }

    /** Problem title; defaults to the HTTP reason phrase when null. */
    public String getTitle() {
        return title;
    }

    public String getCode() {
        return code;
    }

    /** Extra problem properties (merged into the JSON body). */
    public Map<String, Object> getProperties() {
        return Collections.unmodifiableMap(properties);
    }

    /** Adds an extra property to the problem body; returns {@code this} for chaining. */
    public ApiException with(String key, Object value) {
        properties.put(key, value);
        return this;
    }
}
