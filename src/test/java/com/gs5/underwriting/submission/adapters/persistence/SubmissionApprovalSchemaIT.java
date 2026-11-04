package com.gs5.underwriting.submission.adapters.persistence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jdbc.test.autoconfigure.DataJdbcTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureDataSourceInitialization;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The database, not the application, is the last line of defence for approval (design section 19.8,
 * D9). These tests run the real V1 to V3 migrations on PostgreSQL and write rows that the application
 * would never write, to show the schema refuses them.
 *
 * <p>The tenant-mismatch and no-cascade tests fail on the current V3: its foreign key names only the
 * submission, and it cascades on delete. The one-approval test is a guard and passes at HEAD.
 */
@DataJdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@AutoConfigureDataSourceInitialization
@Testcontainers
class SubmissionApprovalSchemaIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    private static final UUID TENANT_A = UUID.fromString("0a000000-0000-0000-0000-00000000000a");
    private static final UUID TENANT_B = UUID.fromString("0b000000-0000-0000-0000-00000000000b");
    private static final Instant REGISTERED_AT = Instant.parse("2026-11-01T10:15:30Z");
    private static final Instant APPROVED_AT = Instant.parse("2026-11-02T08:00:00Z");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Nested
    @DisplayName("An approval row against the schema")
    class ApprovalRowConstraints {

        @Test
        @DisplayName("an approval cannot name a submission that belongs to another organisation (BR-17, D9)")
        void anApprovalCannotReferenceASubmissionOfAnotherTenant() {
            var submission = insertSubmission(TENANT_A, "REF-1");

            assertThatThrownBy(() -> insertApproval(submission, TENANT_B, "uw-02"))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("deleting an approved submission is refused, not cascaded into its approval (D9)")
        void deletingAnApprovedSubmissionIsRefusedNotCascaded() {
            var submission = insertSubmission(TENANT_A, "REF-2");
            insertApproval(submission, TENANT_A, "uw-02");

            assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM submissions WHERE id = ?", submission))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("guard: a second approval of one submission is refused by the primary key (BR-36)")
        void aSecondApprovalOfOneSubmissionIsRefused() {
            var submission = insertSubmission(TENANT_A, "REF-3");
            insertApproval(submission, TENANT_A, "uw-02");

            assertThatThrownBy(() -> insertApproval(submission, TENANT_A, "suw-01"))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    // ------------------------------------------------------------------ helpers

    private UUID insertSubmission(UUID tenantId, String externalRef) {
        var id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO submissions (id, tenant_id, external_ref, insured_limit, registered_by, registered_at, version)
                VALUES (?, ?, ?, ?, ?, ?, 0)
                """, id, tenantId, externalRef, new BigDecimal("100.00"), "uw-01",
                Timestamp.from(REGISTERED_AT));
        return id;
    }

    private void insertApproval(UUID submissionId, UUID tenantId, String approvedBy) {
        jdbcTemplate.update("""
                INSERT INTO submission_approvals (submission_id, tenant_id, approved_by, approved_at, version)
                VALUES (?, ?, ?, ?, 0)
                """, submissionId, tenantId, approvedBy, Timestamp.from(APPROVED_AT));
    }
}
