package com.saadat.common.error;

import java.net.URI;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

/** Factory for RFC 7807 {@link ProblemDetail}s with the project's {@code type} URI convention. */
public final class Problems {

    /** Base of every problem {@code type}; the slug is appended. */
    public static final String TYPE_BASE = "https://api.saadatu-aldarein.com/errors/";

    public static final String PROP_CODE = "code";
    public static final String PROP_ERRORS = "errors";

    private Problems() {
    }

    public static URI type(String slug) {
        return URI.create(TYPE_BASE + slug);
    }

    /** Slug derived from the reason phrase, e.g. 404 → "not-found". */
    public static String slugFor(HttpStatusCode status) {
        HttpStatus resolved = HttpStatus.resolve(status.value());
        if (resolved == null) {
            return "error-" + status.value();
        }
        return resolved.getReasonPhrase().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
    }

    public static ProblemDetail of(HttpStatusCode status, String slug, String title, String detail) {
        ProblemDetail pd = ProblemDetail.forStatus(status);
        pd.setType(type(slug == null ? slugFor(status) : slug));
        if (title != null) {
            pd.setTitle(title);
        }
        if (detail != null) {
            pd.setDetail(detail);
        }
        return pd;
    }

    public static ProblemDetail of(HttpStatusCode status, String detail) {
        return of(status, null, null, detail);
    }
}
