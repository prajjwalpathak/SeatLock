package com.seatlock.common.exception;

import com.seatlock.common.web.RequestIdFilter;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationTrustResolver;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns every error into an RFC 7807 ProblemDetail with two extra properties:
 * {@code code} (stable, machine-readable) and {@code requestId} (for log correlation).
 * Spring MVC's own exceptions (404, 405, 415, malformed JSON...) are handled by the base class.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final AuthenticationTrustResolver TRUST_RESOLVER = new AuthenticationTrustResolverImpl();

    /** One invalid field in a request body. */
    public record FieldViolation(String field, String message) {}

    @ExceptionHandler(NotFoundException.class)
    ProblemDetail handleNotFound(NotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "Resource not found", ex.getMessage(), ex.getCode());
    }

    @ExceptionHandler(ConflictException.class)
    ProblemDetail handleConflict(ConflictException ex) {
        return problem(HttpStatus.CONFLICT, "Conflict", ex.getMessage(), ex.getCode());
    }

    @ExceptionHandler(UnauthorizedException.class)
    ProblemDetail handleUnauthorized(UnauthorizedException ex) {
        return problem(HttpStatus.UNAUTHORIZED, "Unauthorized", ex.getMessage(), ex.getCode());
    }

    @ExceptionHandler(InvalidRequestException.class)
    ProblemDetail handleInvalidRequest(InvalidRequestException ex) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid request", ex.getMessage(), ex.getCode());
    }

    /**
     * Failed login → 401. Same answer for "no such user" and "wrong password", so the
     * endpoint can't be used to find out which e-mails have accounts.
     */
    @ExceptionHandler(BadCredentialsException.class)
    ResponseEntity<ProblemDetail> handleBadCredentials(BadCredentialsException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(problem(HttpStatus.UNAUTHORIZED,
                "Invalid credentials", "Invalid e-mail or password.", "INVALID_CREDENTIALS"));
    }

    /**
     * Bad signature, expired, wrong issuer/audience, malformed → 401 INVALID_TOKEN. The client
     * should sign in again. The precise reason stays in our logs, not the response.
     */
    @ExceptionHandler(InvalidBearerTokenException.class)
    ResponseEntity<ProblemDetail> handleInvalidToken(InvalidBearerTokenException ex) {
        log.debug("Rejected bearer token: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\"") // RFC 6750 §3.1
                .body(problem(HttpStatus.UNAUTHORIZED, "Invalid token",
                        "The access token is invalid or has expired.", "INVALID_TOKEN"));
    }

    /** Missing/invalid credentials → 401. The exception message is never echoed (it can reveal why). */
    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<ProblemDetail> handleAuthentication(AuthenticationException ex) {
        return authenticationRequired();
    }

    /**
     * Not allowed → 403, but an anonymous caller gets 401 instead (logging in might help).
     * Needed here because this advice sees exceptions from controllers (e.g. @PreAuthorize)
     * before Spring Security's ExceptionTranslationFilter could.
     */
    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException ex) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || TRUST_RESOLVER.isAnonymous(auth)) {
            return authenticationRequired();
        }
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(problem(HttpStatus.FORBIDDEN, "Access denied",
                "You do not have permission to perform this action.", "ACCESS_DENIED"));
    }

    /**
     * Last-resort handler: log the stack trace, never leak internals to the client.
     */
    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error",
                "An unexpected error occurred. Quote the requestId when reporting this.",
                "INTERNAL_ERROR");
    }

    /** @Valid @RequestBody failures → 400 with a list of field violations. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail pd = problem(HttpStatus.BAD_REQUEST, "Validation failed",
                "One or more fields are invalid.", "VALIDATION_FAILED");
        List<FieldViolation> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new FieldViolation(fe.getField(),
                        Objects.requireNonNullElse(fe.getDefaultMessage(), "is invalid")))
                .toList();
        pd.setProperty("errors", errors);
        return handleExceptionInternal(ex, pd, headers, HttpStatus.BAD_REQUEST, request);
    }

    /** Adds code + requestId to every ProblemDetail the base class produces (404, 405, ...). */
    @Override
    protected ResponseEntity<Object> createResponseEntity(Object body, HttpHeaders headers,
                                                          HttpStatusCode statusCode, WebRequest request) {
        if (body instanceof ProblemDetail pd) {
            if (pd.getProperties() == null || !pd.getProperties().containsKey("code")) {
                HttpStatus resolved = HttpStatus.resolve(statusCode.value());
                pd.setProperty("code", resolved != null ? resolved.name() : "HTTP_" + statusCode.value());
            }
            addRequestId(pd);
        }
        return super.createResponseEntity(body, headers, statusCode, request);
    }

    private static ResponseEntity<ProblemDetail> authenticationRequired() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer") // RFC 6750: the scheme to use
                .body(problem(HttpStatus.UNAUTHORIZED, "Authentication required",
                        "Sign in to access this resource.", "AUTHENTICATION_REQUIRED"));
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail, String code) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setTitle(title);
        pd.setProperty("code", code);
        addRequestId(pd);
        return pd;
    }

    private static void addRequestId(ProblemDetail pd) {
        String requestId = MDC.get(RequestIdFilter.MDC_KEY);
        if (requestId != null) {
            pd.setProperty("requestId", requestId);
        }
    }
}