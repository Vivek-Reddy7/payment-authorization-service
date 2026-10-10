package com.vivekreddy.payments.service;

import com.vivekreddy.payments.domain.Authorization;
import com.vivekreddy.payments.domain.IdempotencyRecord;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists an authorization and claims its idempotency key together,
 * atomically, in their own independent transaction.
 *
 * <p>Both writes have to be one unit. A separate "insert the idempotency
 * record afterwards" step was tried first and rejected for two reasons:
 *
 * <p>{@code IdempotencyRecordRepository.save()} will not do it safely on its
 * own. Spring Data JPA's default new-entity check looks for a null id or a
 * {@code @Version} field; {@link IdempotencyRecord} has neither, since its id
 * is the caller-assigned key. So {@code save()} calls
 * {@code entityManager.merge()} rather than {@code persist()}. merge() on a
 * key no other transaction has committed yet finds nothing and quietly falls
 * back to an insert -- but on a key a concurrent winner HAS since committed,
 * merge() finds that row and issues an UPDATE instead, silently overwriting
 * which authorization the key points at. No exception is raised either way,
 * and two concurrent callers can each believe their own authorization is the
 * one of record.
 *
 * <p>And running just the idempotency insert in its own {@code REQUIRES_NEW}
 * transaction, after the authorization was saved in the caller's still-open
 * transaction, does not work either: the idempotency record's foreign key
 * references the authorization row, which is only staged, not committed, in
 * the suspended caller transaction -- an independent transaction cannot see
 * it, and the insert fails on the foreign key before it ever reaches the
 * unique key it is meant to test.
 *
 * <p>So both rows are written here, together, with {@code persist()} (always
 * an INSERT, never the merge ambiguity above), in one {@code REQUIRES_NEW}
 * transaction with its own connection and persistence context. A genuine
 * race then has exactly one real outcome: the first attempt commits both
 * rows, and every other attempt's insert hits the real unique-key violation
 * and rolls back its own transaction whole -- the authorization that attempt
 * staged included, with nothing left behind to clean up.
 *
 * <p><b>The violation is not caught here.</b> Once {@code flush()} fails,
 * Hibernate has already marked the underlying transaction unusable; catching
 * the exception and returning normally does not undo that, and Spring then
 * refuses to "commit" a transaction it finds marked rollback-only, throwing
 * {@code UnexpectedRollbackException} instead -- a confusing failure in place
 * of the clean one this class exists to produce. So the exception is left to
 * propagate: Spring's {@code @Transactional} rolls this method's own,
 * independent transaction back the ordinary way, and the caller, running in
 * a different transaction entirely, catches it there to decide what a lost
 * race means.
 */
@Component
class IdempotencyClaimer {

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * @return the saved authorization
     * @throws org.hibernate.exception.ConstraintViolationException if a
     *         concurrent call already holds this idempotency key; nothing
     *         from this call is left committed
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Authorization claim(Authorization authorization, String idempotencyKey,
                               String fingerprint, Instant now) {
        // GenerationType.UUID assigns the id in memory on persist(), with no
        // round trip, so it is available immediately for the record below.
        entityManager.persist(authorization);
        entityManager.persist(
                new IdempotencyRecord(idempotencyKey, fingerprint, authorization.getId(), now));
        entityManager.flush();
        return authorization;
    }
}
