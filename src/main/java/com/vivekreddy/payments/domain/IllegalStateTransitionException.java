package com.vivekreddy.payments.domain;

import java.util.UUID;

/**
 * Thrown when an authorization is asked to make a move its status forbids --
 * capturing something already voided, for instance.
 *
 * <p>This is a 409, not a 400: the request was well formed and would have been
 * valid a moment earlier. The distinction matters to callers, because a 400
 * means "fix your request" and a 409 means "re-read the state".
 */
public class IllegalStateTransitionException extends RuntimeException {

    private final UUID authorizationId;
    private final AuthorizationStatus from;
    private final AuthorizationStatus to;

    public IllegalStateTransitionException(UUID authorizationId,
                                           AuthorizationStatus from,
                                           AuthorizationStatus to) {
        super("authorization %s is %s and cannot become %s"
                .formatted(authorizationId, from, to));
        this.authorizationId = authorizationId;
        this.from = from;
        this.to = to;
    }

    public UUID getAuthorizationId() {
        return authorizationId;
    }

    public AuthorizationStatus getFrom() {
        return from;
    }

    public AuthorizationStatus getTo() {
        return to;
    }
}
