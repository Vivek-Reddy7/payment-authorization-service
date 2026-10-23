package com.vivekreddy.payments.domain;

import java.time.Instant;

/**
 * One row the {@code audit_authorization_invariants} stored procedure wrote
 * to {@code audit_findings} (Oracle profile only -- see
 * {@code db/migration-oracle/V2__invariant_audit.sql}).
 *
 * <p>A finding describes a row that should be unreachable given everything the
 * application code enforces on the way in, so each one found is either a real
 * bug or a write that bypassed the application entirely.
 */
public record AuditFinding(
        String ruleViolated,
        String authorizationId,
        String detail,
        Instant detectedAt) {
}
