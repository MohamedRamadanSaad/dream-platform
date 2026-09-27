package com.saadat.common.error;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;

/**
 * Writes a {@link ProblemDetail} directly to the servlet response. Used from filters and security
 * handlers, which run outside Spring MVC (so {@link ApiExceptionHandler} does not apply there).
 */
public final class ProblemWriter {

    private ProblemWriter() {
    }

    public static void write(HttpServletRequest request, HttpServletResponse response, ObjectMapper objectMapper,
                             ProblemDetail problem) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", problem.getType() == null ? null : problem.getType().toString());
        body.put("title", problem.getTitle());
        body.put("status", problem.getStatus());
        if (problem.getDetail() != null) {
            body.put("detail", problem.getDetail());
        }
        body.put("instance", request.getRequestURI());
        if (problem.getProperties() != null) {
            body.putAll(problem.getProperties());
        }
        response.setStatus(problem.getStatus());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
