-- V2 (Oracle only) · a stored procedure that audits cross-row invariants the
-- Java state machine enforces in-process but that the database never
-- independently re-checks once a row is on disk.
--
-- Why this exists: AuthorizationStatus.canTransitionTo() and the CHECK
-- constraints in V1 each guard one write at a time. Neither can catch a row
-- that reached an impossible state some other way -- a bug in a future code
-- path that writes directly through JDBC, a manual fix applied during an
-- incident, or a migration that touched these tables without going through
-- the application. A write-time guard only ever sees the write it was called
-- for; this procedure re-derives the complete set of rows that should be
-- impossible, from the data as it actually sits, not from the claims the
-- application code happened to make on the way in. Same discipline as
-- investment-intelligence's grant audit: enumerate everything, not just what
-- the last test happened to check.
--
-- Lives in its own location (db/migration-oracle), applied only under the
-- oracle profile, because PL/SQL is not portable to H2 or MySQL and this
-- project runs on those by default. Exercised by running it against a real
-- instance, not by CI -- the same pattern already established for the mysql
-- profile.

CREATE TABLE audit_findings (
    id                NUMBER GENERATED ALWAYS AS IDENTITY,
    audit_run_id      VARCHAR2(36)  NOT NULL,
    rule_violated     VARCHAR2(64)  NOT NULL,
    authorization_id  VARCHAR2(36)  NOT NULL,
    detail            VARCHAR2(500) NOT NULL,
    detected_at       TIMESTAMP(6)  NOT NULL,

    CONSTRAINT pk_audit_findings PRIMARY KEY (id)
);

CREATE INDEX idx_audit_findings_run ON audit_findings (audit_run_id);

CREATE OR REPLACE PROCEDURE audit_authorization_invariants (
    p_audit_run_id     IN  VARCHAR2,
    p_violation_count  OUT NUMBER
) AS
BEGIN
    -- Rule 1: ORPHANED_AUTHORIZATION. The only path that ever creates an
    -- authorization (IdempotencyClaimer.claim) inserts it and its
    -- idempotency_records row together, in one transaction, so every
    -- authorization should be referenced by at least one such row. The
    -- foreign key on idempotency_records only checks the direction from
    -- idempotency_records to authorizations; nothing in the schema enforces
    -- the reverse. An authorization with no referencing row means that
    -- atomicity was violated -- exactly the failure mode the earlier
    -- concurrency bug produced before IdempotencyClaimer existed.
    INSERT INTO audit_findings
        (audit_run_id, rule_violated, authorization_id, detail, detected_at)
    SELECT
        p_audit_run_id,
        'ORPHANED_AUTHORIZATION',
        a.id,
        'authorization has no idempotency_records row referencing it',
        SYSTIMESTAMP
    FROM authorizations a
    WHERE NOT EXISTS (
        SELECT 1 FROM idempotency_records i WHERE i.authorization_id = a.id
    );

    -- Rule 2: TERMINAL_WITHOUT_TRANSITION. Every authorization is inserted as
    -- APPROVED or DECLINED (Authorization.approved()/declined()); CAPTURED
    -- and VOIDED are only reachable through transitionTo(), which Hibernate
    -- persists as an UPDATE and so always advances @Version past its initial
    -- 0. A CAPTURED or VOIDED row still at version 0 was never updated to get
    -- there, meaning it was inserted directly in a terminal post-approval
    -- state -- something the application code does not do today, and should
    -- never start doing silently.
    INSERT INTO audit_findings
        (audit_run_id, rule_violated, authorization_id, detail, detected_at)
    SELECT
        p_audit_run_id,
        'TERMINAL_WITHOUT_TRANSITION',
        a.id,
        'status is ' || a.status || ' but version=0, so no update ever moved it there',
        SYSTIMESTAMP
    FROM authorizations a
    WHERE a.status IN ('CAPTURED', 'VOIDED')
      AND a.version = 0;

    -- Rule 3: DECLINE_REASON_INCONSISTENT. Restates V1's
    -- ck_authorizations_decline_reason as an independent, re-runnable check
    -- rather than trusting the write-time constraint never to have been
    -- bypassed (a direct DML statement run with constraint checking disabled
    -- during an incident is the realistic way this could happen).
    INSERT INTO audit_findings
        (audit_run_id, rule_violated, authorization_id, detail, detected_at)
    SELECT
        p_audit_run_id,
        'DECLINE_REASON_INCONSISTENT',
        a.id,
        CASE
            WHEN a.status = 'DECLINED' THEN 'status is DECLINED but decline_reason is null'
            ELSE 'status is ' || a.status || ' but decline_reason is not null'
        END,
        SYSTIMESTAMP
    FROM authorizations a
    WHERE (a.status = 'DECLINED' AND a.decline_reason IS NULL)
       OR (a.status <> 'DECLINED' AND a.decline_reason IS NOT NULL);

    SELECT COUNT(*) INTO p_violation_count
    FROM audit_findings
    WHERE audit_run_id = p_audit_run_id;

    COMMIT;
END audit_authorization_invariants;
/
