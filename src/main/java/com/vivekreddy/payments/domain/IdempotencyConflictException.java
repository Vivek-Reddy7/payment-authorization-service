package com.vivekreddy.payments.domain;

/**
 * Thrown when an {@code Idempotency-Key} is reused with a different body.
 *
 * <p>Replaying the original response would be wrong -- the client asked for
 * something else and would receive an answer to a question it did not pose.
 * Creating a second authorization would be worse, because it defeats the point
 * of the key. Refusing is the only safe option.
 */
public class IdempotencyConflictException extends RuntimeException {

    private final String idempotencyKey;

    public IdempotencyConflictException(String idempotencyKey) {
        super("idempotency key %s was already used with a different request body"
                .formatted(idempotencyKey));
        this.idempotencyKey = idempotencyKey;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }
}
