package com.vivekreddy.payments.service;

import com.vivekreddy.payments.api.dto.AuthorizationRequest;
import com.vivekreddy.payments.domain.AuditFinding;
import com.vivekreddy.payments.domain.Authorization;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves {@code audit_authorization_invariants} actually catches the rows it
 * claims to, against a real Oracle instance -- not just that it compiles.
 *
 * <p>Skipped outside the {@code oracle} profile, the same way this project
 * treats MySQL: exercised by running it against a real instance, not by CI,
 * because neither H2 nor the default build has a real Oracle database behind
 * it. Run with: {@code mvn test -Dspring-boot.run.profiles=oracle
 * -Dtest=InvariantAuditOracleTest -Dspring.profiles.active=oracle} against a
 * local Oracle Database Free 23ai container.
 *
 * <p>Rows 1 and 2 below are inserted with plain JDBC rather than through
 * {@link Authorization}, deliberately: the entity has no constructor that can
 * produce either shape (no factory method inserts a row already CAPTURED or
 * VOIDED, and {@code @Version} is Hibernate-managed, not settable), which is
 * exactly why a database-level audit is the only thing that can still catch
 * them if some future write path bypasses the entity. The third invariant
 * (decline-reason consistency) is not mutation-tested here: V1's own
 * {@code ck_authorizations_decline_reason} CHECK constraint already rejects
 * that row at the INSERT, before the procedure would ever see it, so there is
 * no ordinary way to produce it short of disabling the constraint -- not a
 * gap in this test, but the constraint doing its job.
 */
@SpringBootTest
class InvariantAuditOracleTest {

    private static final String PAN = "5555555555554444";

    @Autowired
    private Environment environment;

    @Autowired
    private InvariantAuditor auditor;

    @Autowired
    private AuthorizationService authorizationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void requireOracleProfile() {
        Assumptions.assumeTrue(
                List.of(environment.getActiveProfiles()).contains("oracle"),
                "audit_authorization_invariants only exists under the oracle profile; "
                        + "run with -Dspring.profiles.active=oracle against a local Oracle instance");
    }

    @Test
    @DisplayName("a normally-created, normally-captured authorization raises no finding")
    void cleanAuthorizationRaisesNoFinding() {
        AuthorizationRequest request = new AuthorizationRequest(
                5_000L, "USD", PAN, YearMonth.of(2030, 12), "order-audit-clean");
        Authorization authorization =
                authorizationService.authorize(request, UUID.randomUUID().toString());
        authorizationService.capture(authorization.getId());

        List<AuditFinding> findings = auditor.run();

        assertThat(findings)
                .as("no finding should reference a row written through the real service")
                .noneMatch(f -> f.authorizationId().equals(authorization.getId().toString()));
    }

    @Test
    @DisplayName("catches an authorization with no idempotency_records row pointing at it")
    void catchesAnOrphanedAuthorization() {
        String id = UUID.randomUUID().toString();
        insertAuthorization(id, "APPROVED", null, 0);

        List<AuditFinding> findings = auditor.run();

        assertThat(findings)
                .anyMatch(f -> f.ruleViolated().equals("ORPHANED_AUTHORIZATION")
                        && f.authorizationId().equals(id));
    }

    @Test
    @DisplayName("catches a CAPTURED row that was inserted directly, never updated to get there")
    void catchesATerminalRowAtVersionZero() {
        String id = UUID.randomUUID().toString();
        insertAuthorization(id, "CAPTURED", null, 0);
        insertIdempotencyRecord(id);

        List<AuditFinding> findings = auditor.run();

        assertThat(findings)
                .anyMatch(f -> f.ruleViolated().equals("TERMINAL_WITHOUT_TRANSITION")
                        && f.authorizationId().equals(id));
    }

    private void insertAuthorization(String id, String status, String declineReason, long version) {
        jdbcTemplate.update(
                """
                INSERT INTO authorizations
                    (id, amount_minor, currency, card_last4, card_fingerprint,
                     merchant_reference, status, decline_reason, created_at, updated_at, version)
                VALUES (?, 1000, 'USD', '4444', 'test-fingerprint-not-a-real-hash',
                        ?, ?, ?, SYSTIMESTAMP, SYSTIMESTAMP, ?)
                """,
                id, "order-" + id, status, declineReason, version);
    }

    private void insertIdempotencyRecord(String authorizationId) {
        jdbcTemplate.update(
                """
                INSERT INTO idempotency_records
                    (idempotency_key, request_fingerprint, authorization_id, created_at)
                VALUES (?, 'test-fingerprint', ?, SYSTIMESTAMP)
                """,
                UUID.randomUUID().toString(), authorizationId);
    }
}
