package com.vivekreddy.payments.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The outcome of a request that carried an {@code Idempotency-Key}.
 *
 * <p>Networks retry. A client that times out waiting for an authorization has
 * no way to know whether the card was charged, so it retries -- and without
 * something like this, the cardholder is authorized twice. This is the standard
 * shape for payment APIs and the reason every serious one requires the header.
 *
 * <p>The stored {@code requestFingerprint} is what makes it honest. A key alone
 * would let a client send a $10 request, then reuse the key for $10,000 and
 * receive the cached $10 approval. So the body is hashed too: same key and same
 * body replays the original answer, same key with a different body is a
 * conflict and is rejected rather than guessed at.
 */
@Entity
@Table(name = "idempotency_records")
public class IdempotencyRecord {

    /** The client-supplied key, used directly as the primary key. */
    @Id
    @Column(name = "idempotency_key", nullable = false, updatable = false, length = 64)
    private String idempotencyKey;

    /** SHA-256 of the canonical request body. */
    @Column(name = "request_fingerprint", nullable = false, updatable = false, length = 64)
    private String requestFingerprint;

    /** The authorization the first call produced, replayed on retry. */
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "authorization_id", nullable = false, updatable = false, length = 36)
    private UUID authorizationId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected IdempotencyRecord() {
        // JPA.
    }

    public IdempotencyRecord(String idempotencyKey, String requestFingerprint,
                             UUID authorizationId, Instant createdAt) {
        this.idempotencyKey = idempotencyKey;
        this.requestFingerprint = requestFingerprint;
        this.authorizationId = authorizationId;
        this.createdAt = createdAt;
    }

    public boolean matches(String candidateFingerprint) {
        return requestFingerprint.equals(candidateFingerprint);
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestFingerprint() {
        return requestFingerprint;
    }

    public UUID getAuthorizationId() {
        return authorizationId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
