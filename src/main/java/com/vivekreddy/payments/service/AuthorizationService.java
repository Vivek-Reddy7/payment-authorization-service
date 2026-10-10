package com.vivekreddy.payments.service;

import com.vivekreddy.payments.api.dto.AuthorizationRequest;
import com.vivekreddy.payments.domain.Authorization;
import com.vivekreddy.payments.domain.AuthorizationNotFoundException;
import com.vivekreddy.payments.domain.AuthorizationStatus;
import com.vivekreddy.payments.domain.IdempotencyConflictException;
import com.vivekreddy.payments.domain.IdempotencyRecord;
import com.vivekreddy.payments.repository.AuthorizationRepository;
import com.vivekreddy.payments.repository.IdempotencyRecordRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates authorization: idempotency, decisioning, persistence.
 *
 * <p>The {@link Clock} is injected rather than calling {@code Instant.now()}
 * inline. Time is an input, and a service that reads the wall clock internally
 * can only have its time-dependent behaviour tested by actually waiting.
 */
@Service
public class AuthorizationService {

    private final AuthorizationRepository authorizations;
    private final IdempotencyRecordRepository idempotencyRecords;
    private final IdempotencyClaimer idempotencyClaimer;
    private final DecisionEngine decisionEngine;
    private final CardFingerprinter fingerprinter;
    private final Clock clock;

    public AuthorizationService(AuthorizationRepository authorizations,
                                IdempotencyRecordRepository idempotencyRecords,
                                IdempotencyClaimer idempotencyClaimer,
                                DecisionEngine decisionEngine,
                                CardFingerprinter fingerprinter,
                                Clock clock) {
        this.authorizations = authorizations;
        this.idempotencyRecords = idempotencyRecords;
        this.idempotencyClaimer = idempotencyClaimer;
        this.decisionEngine = decisionEngine;
        this.fingerprinter = fingerprinter;
        this.clock = clock;
    }

    /**
     * Authorizes, or replays a previous identical request.
     *
     * <p>A decline is a successful call that returns DECLINED, not an error.
     * Declines are an ordinary outcome of a working payment system, and modelling
     * them as exceptions makes callers treat a normal answer as a failure.
     *
     * @param idempotencyKey required; retries carrying the same key and body get
     *                       the original authorization rather than a second one
     * @throws IdempotencyConflictException if the key was used with a different body
     */
    @Transactional
    public Authorization authorize(AuthorizationRequest request, String idempotencyKey) {
        String fingerprint = fingerprinter.hash(canonical(request));

        var existing = idempotencyRecords.findById(idempotencyKey);
        if (existing.isPresent()) {
            return replayOrConflict(existing.get(), fingerprint);
        }

        Instant now = now();
        YearMonth today = YearMonth.from(now.atZone(ZoneOffset.UTC));
        Decision decision = decisionEngine.decide(
                request.pan(), request.expiry(), request.amountMinor(), today);

        String cardFingerprint = fingerprinter.fingerprint(request.pan());
        String last4 = CardFingerprinter.last4(request.pan());

        Authorization authorization = decision.approved()
                ? Authorization.approved(request.amountMinor(), request.currency(),
                        last4, cardFingerprint, request.merchantReference(), now)
                : Authorization.declined(request.amountMinor(), request.currency(),
                        last4, cardFingerprint, request.merchantReference(),
                        decision.reason(), now);

        // Declines are recorded too. A client retrying a declined request must
        // get the same decline, not a fresh attempt -- otherwise the key stops
        // being a guarantee precisely when the caller is retrying hardest.
        //
        // The authorization and its idempotency claim are written together, in
        // their own independent transaction (see IdempotencyClaimer): either
        // both commit, or a concurrent winner's key collision rolls both back,
        // leaving nothing behind for this losing attempt to clean up. The
        // exception is caught here, in this (separate, unaffected) transaction
        // -- not inside the claim itself, which would leave Spring trying to
        // commit a transaction Hibernate has already marked unusable.
        try {
            return idempotencyClaimer.claim(authorization, idempotencyKey, fingerprint, now);
        } catch (DataIntegrityViolationException | ConstraintViolationException e) {
            // Lost the race: a concurrent request claimed this key first and
            // already committed. The claim's own insert only fails this way
            // once that row genuinely exists to collide with, so it is
            // visible to this read.
            IdempotencyRecord winner = idempotencyRecords.findById(idempotencyKey)
                    .orElseThrow(() -> new IllegalStateException(
                            "lost the idempotency race for " + idempotencyKey
                                    + " but found no winning record"));
            return replayOrConflict(winner, fingerprint);
        }
    }

    private Authorization replayOrConflict(IdempotencyRecord record, String fingerprint) {
        if (!record.matches(fingerprint)) {
            throw new IdempotencyConflictException(record.getIdempotencyKey());
        }
        return authorizations.findById(record.getAuthorizationId())
                .orElseThrow(() ->
                        new AuthorizationNotFoundException(record.getAuthorizationId()));
    }

    @Transactional
    public Authorization capture(UUID id) {
        return transition(id, AuthorizationStatus.CAPTURED);
    }

    @Transactional
    public Authorization voidAuthorization(UUID id) {
        return transition(id, AuthorizationStatus.VOIDED);
    }

    @Transactional(readOnly = true)
    public Authorization get(UUID id) {
        return authorizations.findById(id)
                .orElseThrow(() -> new AuthorizationNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public Page<Authorization> list(AuthorizationStatus status, Pageable pageable) {
        return status == null
                ? authorizations.findAll(pageable)
                : authorizations.findByStatus(status, pageable);
    }

    private Authorization transition(UUID id, AuthorizationStatus target) {
        Authorization authorization = authorizations.findById(id)
                .orElseThrow(() -> new AuthorizationNotFoundException(id));
        // Throws if the move is illegal. The entity owns that rule.
        authorization.transitionTo(target, now());
        return authorizations.save(authorization);
    }

    /**
     * The current instant, truncated to the precision the database actually
     * stores.
     *
     * <p>Without the truncation, the timestamp returned when an authorization is
     * created differs from the one returned by every later read of it. The create
     * response is serialised from the in-memory entity, which carries whatever
     * precision the platform clock offers -- nanoseconds on Linux -- while the
     * column is TIMESTAMP(6) and keeps microseconds. The value silently loses its
     * tail on the way to disk.
     *
     * <p>Found by CI on Linux after passing on macOS, where the clock happens to
     * tick in microseconds already and the two agreed by luck. A client caching a
     * create response and later comparing it against a GET would have seen two
     * different timestamps for one authorization.
     */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    /**
     * A stable string for the request, so the same logical request always hashes
     * the same way.
     *
     * <p>Built field by field rather than by serialising the object, because JSON
     * key order and formatting are not guaranteed stable -- and a fingerprint
     * that changes when the serialiser changes would turn every retry after an
     * upgrade into a false conflict.
     */
    private static String canonical(AuthorizationRequest r) {
        return String.join("|",
                String.valueOf(r.amountMinor()),
                r.currency(),
                r.pan(),
                r.expiry().toString(),
                r.merchantReference());
    }
}
