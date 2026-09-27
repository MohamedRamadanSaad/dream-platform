package com.saadat.common.error;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * The single place that turns exceptions into RFC 7807 problems
 * ({@code type = https://api.saadatu-aldarein.com/errors/<slug>}).
 */
@Slf4j
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ProblemDetail> handleApi(ApiException ex, HttpServletRequest request) {
        ProblemDetail pd = Problems.of(ex.getStatus(), ex.getSlug(), ex.getTitle(), ex.getMessage());
        if (ex.getCode() != null) {
            pd.setProperty(Problems.PROP_CODE, ex.getCode());
        }
        for (Map.Entry<String, Object> e : ex.getProperties().entrySet()) {
            pd.setProperty(e.getKey(), e.getValue());
        }
        if (ex.getStatus().is5xxServerError()) {
            log.warn("API error {} on {}: {}", ex.getStatus().value(), request.getRequestURI(), ex.getMessage());
        }
        return build(pd, request);
    }

    /** Bean Validation on {@code @Valid @RequestBody} → 400 with {@code errors: {field: [messages]}}. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleBodyValidation(MethodArgumentNotValidException ex,
                                                              HttpServletRequest request) {
        Map<String, List<String>> errors = new LinkedHashMap<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            errors.computeIfAbsent(fe.getField(), k -> new ArrayList<>()).add(messageOf(fe));
        }
        for (ObjectError oe : ex.getBindingResult().getGlobalErrors()) {
            errors.computeIfAbsent(oe.getObjectName(), k -> new ArrayList<>()).add(messageOf(oe));
        }
        return validationProblem(errors, request);
    }

    /** Bean Validation on {@code @Validated} method params / services. */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ProblemDetail> handleConstraintViolation(ConstraintViolationException ex,
                                                                   HttpServletRequest request) {
        Map<String, List<String>> errors = new LinkedHashMap<>();
        for (ConstraintViolation<?> v : ex.getConstraintViolations()) {
            String path = v.getPropertyPath() == null ? "" : v.getPropertyPath().toString();
            String field = path.contains(".") ? path.substring(path.lastIndexOf('.') + 1) : path;
            errors.computeIfAbsent(field, k -> new ArrayList<>()).add(v.getMessage());
        }
        return validationProblem(errors, request);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ProblemDetail> handleMethodValidation(HandlerMethodValidationException ex,
                                                               HttpServletRequest request) {
        ProblemDetail pd = Problems.of(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed",
                "Request parameters are invalid");
        return build(pd, request);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingRequestHeaderException.class})
    public ResponseEntity<ProblemDetail> handleBadRequest(Exception ex, HttpServletRequest request) {
        ProblemDetail pd = Problems.of(HttpStatus.BAD_REQUEST, "malformed-request", "Malformed request",
                "The request could not be read");
        return build(pd, request);
    }

    /** Spring MVC exceptions that already know their status (404 no route, 405, 415, missing param...). */
    @ExceptionHandler({ErrorResponseException.class, ResponseStatusException.class, NoResourceFoundException.class,
            HttpRequestMethodNotSupportedException.class, HttpMediaTypeNotSupportedException.class,
            MissingServletRequestParameterException.class})
    public ResponseEntity<ProblemDetail> handleErrorResponse(Exception ex, HttpServletRequest request) {
        HttpStatusCode status = HttpStatus.INTERNAL_SERVER_ERROR;
        String detail = null;
        if (ex instanceof ErrorResponse er) {
            status = er.getStatusCode();
            detail = er.getBody().getDetail();
        }
        ProblemDetail pd = Problems.of(status, detail);
        return build(pd, request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        return build(Problems.of(HttpStatus.FORBIDDEN, "forbidden", null, "Access denied"), request);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ProblemDetail> handleAuthentication(AuthenticationException ex,
                                                              HttpServletRequest request) {
        return build(Problems.of(HttpStatus.UNAUTHORIZED, "unauthenticated", null, "Authentication required"),
                request);
    }

    @ExceptionHandler({DataIntegrityViolationException.class, OptimisticLockingFailureException.class})
    public ResponseEntity<ProblemDetail> handleDataConflict(Exception ex, HttpServletRequest request) {
        log.info("Data conflict on {}: {}", request.getRequestURI(), ex.getClass().getSimpleName());
        return build(Problems.of(HttpStatus.CONFLICT, "conflict", null, "The request conflicts with current state"),
                request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return build(Problems.of(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", null, "Unexpected error"),
                request);
    }

    // ------------------------------------------------------------------------------------------

    private ResponseEntity<ProblemDetail> validationProblem(Map<String, List<String>> errors,
                                                           HttpServletRequest request) {
        ProblemDetail pd = Problems.of(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed",
                "One or more fields are invalid");
        pd.setProperty(Problems.PROP_ERRORS, errors);
        return build(pd, request);
    }

    private static ResponseEntity<ProblemDetail> build(ProblemDetail pd, HttpServletRequest request) {
        if (pd.getInstance() == null) {
            pd.setInstance(URI.create(request.getRequestURI()));
        }
        return ResponseEntity.status(pd.getStatus()).body(pd);
    }

    private static String messageOf(ObjectError error) {
        String msg = error.getDefaultMessage();
        return msg == null ? "invalid" : msg;
    }
}
