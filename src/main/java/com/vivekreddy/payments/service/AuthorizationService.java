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
import java.util.UUID;
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
    private final DecisionEngine decisionEngine;
    private final CardFingerprinter fingerprinter;
    private final Clock clock;

    public AuthorizationService(AuthorizationRepository authorizations,
                                IdempotencyRecordRepository idempotencyRecords,
                                DecisionEngine decisionEngine,
                                CardFingerprinter fingerprinter,
                                Clock clock) {
        this.authorizations = authorizations;
        this.idempotencyRecords = idempotencyRecords;
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

        // The replay check and the insert share one transaction, so two
        // concurrent retries cannot both pass the check and both authorize. The
        // primary key on idempotency_key is the backstop if they somehow do:
        // the second insert violates it and that transaction rolls back.
        var existing = idempotencyRecords.findById(idempotencyKey);
        if (existing.isPresent()) {
            IdempotencyRecord record = existing.get();
            if (!record.matches(fingerprint)) {
                throw new IdempotencyConflictException(idempotencyKey);
            }
            return authorizations.findById(record.getAuthorizationId())
                    .orElseThrow(() ->
                            new AuthorizationNotFoundException(record.getAuthorizationId()));
        }

        Instant now = clock.instant();
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

        Authorization saved = authorizations.save(authorization);

        // Declines are recorded too. A client retrying a declined request must
        // get the same decline, not a fresh attempt -- otherwise the key stops
        // being a guarantee precisely when the caller is retrying hardest.
        idempotencyRecords.save(
                new IdempotencyRecord(idempotencyKey, fingerprint, saved.getId(), now));

        return saved;
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
        authorization.transitionTo(target, clock.instant());
        return authorizations.save(authorization);
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
