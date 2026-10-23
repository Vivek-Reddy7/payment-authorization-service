-- V1 (Oracle) · authorizations and idempotency records
--
-- An Oracle-native translation of db/migration/V1, not a shared file, because
-- the two schemas cannot actually be shared: Oracle has no BIGINT type at
-- all (NUMBER is the only integer type Oracle recognises; attempting BIGINT
-- here fails with ORA-00902, found running this migration against a real
-- Oracle instance for the first time), and VARCHAR2 is the type Oracle
-- actually wants, VARCHAR being a deprecated synonym. Rather than force one
-- file to speak three dialects, Oracle gets its own complete migration
-- history, starting here. The content and every design decision in the
-- comments of db/migration/V1 (CHAR vs VARCHAR, minor-unit money, the
-- decline-reason CHECK) apply identically; only the type spellings differ.
--
-- Forward-only, same as db/migration/V1: no down script, fix forward with V3.

CREATE TABLE authorizations (
    id                  VARCHAR2(36) NOT NULL,
    amount_minor        NUMBER(19)   NOT NULL,
    currency            VARCHAR2(3)  NOT NULL,
    card_last4          VARCHAR2(4)  NOT NULL,
    card_fingerprint    VARCHAR2(64) NOT NULL,
    merchant_reference  VARCHAR2(64) NOT NULL,
    status              VARCHAR2(16) NOT NULL,
    decline_reason      VARCHAR2(32) NULL,
    created_at          TIMESTAMP(6) NOT NULL,
    updated_at          TIMESTAMP(6) NOT NULL,
    version             NUMBER(19)   DEFAULT 0 NOT NULL,

    CONSTRAINT pk_authorizations PRIMARY KEY (id),

    CONSTRAINT ck_authorizations_decline_reason CHECK (
        (status = 'DECLINED' AND decline_reason IS NOT NULL)
        OR (status <> 'DECLINED' AND decline_reason IS NULL)
    ),
    CONSTRAINT ck_authorizations_amount_positive CHECK (amount_minor > 0)
);

CREATE INDEX idx_authorizations_status_created
    ON authorizations (status, created_at);

CREATE INDEX idx_authorizations_card_fingerprint
    ON authorizations (card_fingerprint);

CREATE TABLE idempotency_records (
    idempotency_key     VARCHAR2(64) NOT NULL,
    request_fingerprint VARCHAR2(64) NOT NULL,
    authorization_id    VARCHAR2(36) NOT NULL,
    created_at          TIMESTAMP(6) NOT NULL,

    CONSTRAINT pk_idempotency_records PRIMARY KEY (idempotency_key),
    CONSTRAINT fk_idempotency_authorization
        FOREIGN KEY (authorization_id) REFERENCES authorizations (id)
);
