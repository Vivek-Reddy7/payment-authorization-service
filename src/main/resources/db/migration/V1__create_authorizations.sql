-- V1 · authorizations and idempotency records
--
-- Forward-only. There is no down script and there should not be: a rollback
-- that drops a column drops settled financial records, and "undo the schema
-- change" is never the cheapest way out of a bad deploy on a store like this.
-- Fix forward with V2.
--
-- VARCHAR throughout rather than CHAR, including for the values that are
-- genuinely fixed width like the SHA-256 fingerprints and the ISO-4217 code.
-- CHAR space-pads to its declared width on several engines, and a padded value
-- compares unequal to the unpadded one the application just computed -- which
-- would break idempotency lookups in a way that only shows up against one
-- database. The exact widths are asserted by the application's own validation.

CREATE TABLE authorizations (
    id                  VARCHAR(36)  NOT NULL,

    -- Minor units. See Authorization.java for why this is not a DECIMAL or a
    -- DOUBLE: the value is exact and integral by construction.
    amount_minor        BIGINT       NOT NULL,
    currency            VARCHAR(3)   NOT NULL,

    -- The card number itself is deliberately absent. Only these two columns
    -- describe the card, and neither can reconstruct it.
    card_last4          VARCHAR(4)   NOT NULL,
    card_fingerprint    VARCHAR(64)  NOT NULL,

    merchant_reference  VARCHAR(64)  NOT NULL,
    status              VARCHAR(16)  NOT NULL,
    decline_reason      VARCHAR(32)  NULL,

    created_at          TIMESTAMP(6) NOT NULL,
    updated_at          TIMESTAMP(6) NOT NULL,
    version             BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT pk_authorizations PRIMARY KEY (id),

    -- A decline without a reason, or an approval carrying one, means the write
    -- path has a bug. Enforced here because the database is the last component
    -- that still holds when application code is wrong.
    CONSTRAINT ck_authorizations_decline_reason CHECK (
        (status = 'DECLINED' AND decline_reason IS NOT NULL)
        OR (status <> 'DECLINED' AND decline_reason IS NULL)
    ),
    CONSTRAINT ck_authorizations_amount_positive CHECK (amount_minor > 0)
);

-- Supports the status filter on the list endpoint.
CREATE INDEX idx_authorizations_status_created
    ON authorizations (status, created_at);

-- Supports "every authorization on this card" without storing the card.
CREATE INDEX idx_authorizations_card_fingerprint
    ON authorizations (card_fingerprint);

CREATE TABLE idempotency_records (
    -- The client's key IS the primary key. That is what makes a duplicate
    -- impossible rather than merely unlikely: if two concurrent retries both
    -- get past the application-level check, the second insert violates this
    -- constraint and its transaction rolls back.
    idempotency_key     VARCHAR(64)  NOT NULL,
    request_fingerprint VARCHAR(64)  NOT NULL,
    authorization_id    VARCHAR(36)  NOT NULL,
    created_at          TIMESTAMP(6) NOT NULL,

    CONSTRAINT pk_idempotency_records PRIMARY KEY (idempotency_key),
    CONSTRAINT fk_idempotency_authorization
        FOREIGN KEY (authorization_id) REFERENCES authorizations (id)
);
