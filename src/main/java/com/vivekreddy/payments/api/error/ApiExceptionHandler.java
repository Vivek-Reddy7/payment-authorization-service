package com.vivekreddy.payments.api.error;

import com.vivekreddy.payments.domain.AuthorizationNotFoundException;
import com.vivekreddy.payments.domain.IdempotencyConflictException;
import com.vivekreddy.payments.domain.IllegalStateTransitionException;
import java.net.URI;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns domain exceptions into RFC 7807 problem responses.
 *
 * <p>In one place so every error looks the same. Handled per-controller, error
 * shapes drift until clients have to special-case each endpoint.
 *
 * <p>The status codes carry meaning and are chosen rather than defaulted:
 * <ul>
 *   <li><b>400</b> the request is malformed. Fix it and retry.
 *   <li><b>404</b> no such authorization.
 *   <li><b>409</b> the request is fine but conflicts with current state. Re-read,
 *       then decide -- a retry of the identical call will fail identically.
 * </ul>
 *
 * <p>No handler returns the card number or the fingerprint, including in
 * validation messages. An error path is still a response path, and it is the one
 * most likely to be logged verbatim by a caller.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final URI VALIDATION = URI.create("urn:problem:validation-failed");
    private static final URI NOT_FOUND = URI.create("urn:problem:authorization-not-found");
    private static final URI CONFLICT_STATE = URI.create("urn:problem:illegal-state-transition");
    private static final URI CONFLICT_IDEMPOTENCY = URI.create("urn:problem:idempotency-conflict");

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail onValidationFailure(MethodArgumentNotValidException e) {
        // TreeMap so the field order is stable, which makes the responses
        // assertable in tests and diffable in logs.
        Map<String, String> errors = new TreeMap<>();
        e.getBindingResult().getFieldErrors()
                .forEach(f -> errors.put(f.getField(), f.getDefaultMessage()));

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "The request failed validation.");
        problem.setTitle("Validation failed");
        problem.setType(VALIDATION);
        problem.setProperty("errors", errors);
        return problem;
    }

    @ExceptionHandler(AuthorizationNotFoundException.class)
    public ProblemDetail onNotFound(AuthorizationNotFoundException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND, e.getMessage());
        problem.setTitle("Authorization not found");
        problem.setType(NOT_FOUND);
        problem.setProperty("authorizationId", e.getAuthorizationId().toString());
        return problem;
    }

    @ExceptionHandler(IllegalStateTransitionException.class)
    public ProblemDetail onIllegalTransition(IllegalStateTransitionException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, e.getMessage());
        problem.setTitle("Illegal state transition");
        problem.setType(CONFLICT_STATE);
        problem.setProperty("authorizationId", e.getAuthorizationId().toString());
        problem.setProperty("currentStatus", e.getFrom().name());
        problem.setProperty("requestedStatus", e.getTo().name());
        return problem;
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ProblemDetail onIdempotencyConflict(IdempotencyConflictException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, e.getMessage());
        problem.setTitle("Idempotency key reused");
        problem.setType(CONFLICT_IDEMPOTENCY);
        problem.setProperty("idempotencyKey", e.getIdempotencyKey());
        return problem;
    }
}
