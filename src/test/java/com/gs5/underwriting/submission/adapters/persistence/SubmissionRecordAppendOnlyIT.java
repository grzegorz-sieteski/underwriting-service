package com.gs5.underwriting.submission.adapters.persistence;

import com.gs5.underwriting.submission.domain.SubmissionSearchCriteria;
import com.gs5.underwriting.submission.domain.SubmissionState;
import com.gs5.underwriting.submission.domain.SubmissionSummaryView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jdbc.test.autoconfigure.DataJdbcTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureDataSourceInitialization;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The record as the database keeps it (design sections 20 and 21.3, V4): facts are only ever
 * added (BR-44), and the latest state of each submission is one database definition, the
 * {@code submission_current} view (BR-45, BR-46), which carries no internal identity (BR-47).
 *
 * <p>No ambient transaction: a refused statement aborts its transaction, and each check below must
 * read the table again afterwards. {@link FactTables#clear} empties the tables after each test.
 */
@DataJdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@AutoConfigureDataSourceInitialization
@Import({SubmissionQuerySqlAdapter.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
class SubmissionRecordAppendOnlyIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    private static final UUID TENANT_A = UUID.fromString("0a000000-0000-0000-0000-00000000000a");
    private static final UUID TENANT_B = UUID.fromString("0b000000-0000-0000-0000-00000000000b");
    private static final Instant REGISTERED_AT = Instant.parse("2026-11-01T10:15:30Z");
    private static final Instant APPROVED_AT = Instant.parse("2026-11-02T08:00:00Z");
    /** PostgreSQL's insufficient_privilege: what the V4 trigger raises. */
    private static final String REFUSED = "42501";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private SubmissionQuerySqlAdapter queryAdapter;

    private UUID approved;
    private UUID registered;

    @BeforeEach
    void recordOneApprovedAndOneRegisteredSubmission() {
        approved = insertSubmission(TENANT_A, "APPROVED-1", REGISTERED_AT);
        insertApproval(approved, TENANT_A, "uw-02");
        registered = insertSubmission(TENANT_A, "REGISTERED-1", REGISTERED_AT.plusSeconds(60));
    }

    @AfterEach
    void emptyTheTables() {
        FactTables.clear(jdbcTemplate);
    }

    @Nested
    @DisplayName("Recorded facts are never changed or removed (BR-44)")
    class AppendOnly {

        @ParameterizedTest(name = "SC-90: {0} is refused and changes nothing")
        @ValueSource(strings = {
                "UPDATE submissions SET registered_by = 'someone-else'",
                "UPDATE submissions SET insured_limit = 1.00",
                "DELETE FROM submissions WHERE submission_number = 'REGISTERED-1'",
                "TRUNCATE TABLE submissions CASCADE",
                "UPDATE submission_approvals SET approved_by = 'someone-else'",
                "DELETE FROM submission_approvals",
                "TRUNCATE TABLE submission_approvals"
        })
        @DisplayName("SC-90: the database refuses every statement that would rewrite or remove a "
                + "recorded fact, as insufficient privilege, and both facts are still there")
        void everyRewriteOfARecordedFactIsRefused(String statement) {
            // When
            var thrown = catchThrowable(() -> jdbcTemplate.execute(statement));

            // Then
            assertThat(sqlStateOf(thrown)).isEqualTo(REFUSED);
            assertThat(count("SELECT COUNT(*) FROM submissions")).isEqualTo(2);
            assertThat(count("SELECT COUNT(*) FROM submission_approvals")).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT registered_by FROM submissions WHERE id = ?", String.class, registered))
                    .isEqualTo("uw-01");
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT approved_by FROM submission_approvals WHERE submission_id = ?",
                    String.class, approved))
                    .isEqualTo("uw-02");
        }

        @Test
        @DisplayName("SC-90: adding a new fact is still allowed: a further registration inserts")
        void insertingANewFactIsAllowed() {
            insertSubmission(TENANT_A, "NEW-1", REGISTERED_AT.plusSeconds(120));

            assertThat(count("SELECT COUNT(*) FROM submissions")).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("The latest state is one database definition (BR-45, BR-46, BR-47)")
    class LatestStateView {

        @Test
        @DisplayName("SC-91: submission_current shows each submission once, approved once an approval "
                + "is recorded and registered otherwise")
        void theViewShowsTheLatestStateOfEachSubmission() {
            var rows = jdbcTemplate.queryForList(
                    "SELECT submission_number, approved FROM submission_current "
                            + "WHERE tenant_id = ? ORDER BY submission_number", TENANT_A);

            assertThat(rows).hasSize(2);
            assertThat(rows.get(0)).containsEntry("submission_number", "APPROVED-1")
                    .containsEntry("approved", true);
            assertThat(rows.get(1)).containsEntry("submission_number", "REGISTERED-1")
                    .containsEntry("approved", false);
        }

        @Test
        @DisplayName("SC-92: submission_current has no column holding the internal identity (BR-47, D16)")
        void theViewCarriesNoInternalIdentity() {
            List<String> columns = jdbcTemplate.queryForList("""
                    SELECT column_name FROM information_schema.columns
                    WHERE table_name = 'submission_current' ORDER BY ordinal_position
                    """, String.class);

            assertThat(columns).containsExactly("tenant_id", "submission_number", "insured_limit",
                    "registered_by", "registered_at", "approved");
        }

        @Test
        @DisplayName("SC-91: search shows the state the view defines, within the caller's organisation "
                + "only, newest first")
        void searchShowsTheStateTheViewDefines() {
            insertSubmission(TENANT_B, "OTHER-1", REGISTERED_AT.plusSeconds(300));

            var page = queryAdapter.findPage(
                    new SubmissionSearchCriteria(TENANT_A, null, null, null, null, null), 10, 0);

            assertThat(page).extracting(SubmissionSummaryView::submissionNumber,
                            SubmissionSummaryView::state)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple("REGISTERED-1", SubmissionState.REGISTERED),
                            org.assertj.core.groups.Tuple.tuple("APPROVED-1", SubmissionState.APPROVED));
        }
    }

    // ------------------------------------------------------------------ helpers

    private UUID insertSubmission(UUID tenantId, String submissionNumber, Instant registeredAt) {
        var id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO submissions (id, tenant_id, submission_number, insured_limit, registered_by, registered_at, version)
                VALUES (?, ?, ?, ?, ?, ?, 0)
                """, id, tenantId, submissionNumber, new BigDecimal("100.00"), "uw-01",
                Timestamp.from(registeredAt));
        return id;
    }

    private void insertApproval(UUID submissionId, UUID tenantId, String approvedBy) {
        jdbcTemplate.update("""
                INSERT INTO submission_approvals (submission_id, tenant_id, approved_by, approved_at, version)
                VALUES (?, ?, ?, ?, 0)
                """, submissionId, tenantId, approvedBy, Timestamp.from(APPROVED_AT));
    }

    private int count(String sql) {
        return jdbcTemplate.queryForObject(sql, Integer.class);
    }

    private static String sqlStateOf(Throwable thrown) {
        for (var cause = thrown; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) {
                return sql.getSQLState();
            }
        }
        return null;
    }
}
