package com.vivekreddy.payments.service;

import com.vivekreddy.payments.domain.AuditFinding;
import java.sql.Types;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcCall;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.SqlOutParameter;
import org.springframework.jdbc.core.SqlParameter;
import org.springframework.stereotype.Component;

/**
 * Calls the {@code audit_authorization_invariants} Oracle stored procedure
 * and reads back what it found.
 *
 * <p>Plain JDBC rather than a JPA repository: {@code audit_findings} is an
 * Oracle-only artifact (see {@code db/migration-oracle/V2}), not a portable
 * entity the H2 or MySQL profiles can ever have, so it has no business behind
 * the same abstraction as {@link com.vivekreddy.payments.domain.Authorization}.
 * Calling this against a database that has never run that migration (the
 * default H2 profile, or mysql) fails with "procedure does not exist" --
 * which is the correct failure, not one this class should hide.
 */
@Component
public class InvariantAuditor {

    private final JdbcTemplate jdbcTemplate;
    private final SimpleJdbcCall auditCall;

    public InvariantAuditor(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.auditCall = new SimpleJdbcCall(jdbcTemplate)
                .withProcedureName("AUDIT_AUTHORIZATION_INVARIANTS")
                .declareParameters(
                        new SqlParameter("P_AUDIT_RUN_ID", Types.VARCHAR),
                        new SqlOutParameter("P_VIOLATION_COUNT", Types.NUMERIC));
    }

    /**
     * Runs a fresh audit and returns every finding it produced.
     *
     * <p>Each call gets its own {@code audit_run_id}, so concurrent callers
     * (or a test suite running findings-based assertions) never read each
     * other's rows -- the procedure's own writes are scoped by that id, and
     * so is the read here.
     */
    public List<AuditFinding> run() {
        String auditRunId = UUID.randomUUID().toString();

        Map<String, Object> result = auditCall.execute(
                new MapSqlParameterSource().addValue("P_AUDIT_RUN_ID", auditRunId));
        Number violationCount = (Number) result.get("P_VIOLATION_COUNT");

        if (violationCount.intValue() == 0) {
            return List.of();
        }

        return jdbcTemplate.query(
                """
                SELECT rule_violated, authorization_id, detail, detected_at
                FROM audit_findings
                WHERE audit_run_id = ?
                ORDER BY id
                """,
                (rs, rowNum) -> new AuditFinding(
                        rs.getString("rule_violated"),
                        rs.getString("authorization_id"),
                        rs.getString("detail"),
                        rs.getTimestamp("detected_at").toInstant()),
                auditRunId);
    }
}
