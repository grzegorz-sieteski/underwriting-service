package com.gs5.underwriting.submission.adapters.persistence;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jdbc.test.autoconfigure.DataJdbcTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureDataSourceInitialization;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * SEC-09: pins {@code ck_submissions_external_ref_no_control_chars} in {@code V2} on its own.
 *
 * <p>Every row here is written with raw {@code JdbcTemplate}, bypassing the domain, because the
 * domain refuses these references first (BR-29) — so no test going through the useCase or the
 * adapter could ever reach the CHECK, and until this class existed nothing did. Replacing the
 * CHECK with {@code [[:cntrl:]]} left all 44 ITs green. That form is wrong for this column:
 * PostgreSQL's {@code [[:cntrl:]]} does not cover U+0080–U+009F, so the upper-range cases below
 * are the ones that tell the two regexes apart.
 *
 * <p>Non-transactional, like {@code SubmissionAdversarialPersistenceIT}, because a CHECK violation
 * aborts the surrounding PostgreSQL transaction; the table is truncated after each test instead.
 */
@DataJdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@AutoConfigureDataSourceInitialization
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
class SubmissionControlCharacterCheckIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    /** PostgreSQL's SQLSTATE for a CHECK constraint violation. */
    private static final String CHECK_VIOLATION = "23514";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void emptyTheTable() {
        jdbcTemplate.execute("TRUNCATE TABLE submissions");
    }

    private void insertRaw(String externalRef) {
        jdbcTemplate.update("""
                INSERT INTO submissions
                    (id, tenant_id, external_ref, insured_limit, registered_by, registered_at,
                     version)
                VALUES (?, ?, ?, ?, ?, ?, 0)
                """, UUID.randomUUID(), UUID.randomUUID(), externalRef,
                new BigDecimal("1200.00"), "uw-01",
                Timestamp.from(Instant.parse("2026-11-01T10:15:30Z")));
    }

    private static String sqlStateOf(Throwable thrown) {
        for (var cause = thrown; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) {
                return sql.getSQLState();
            }
        }
        return null;
    }

    private int rowCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM submissions", Integer.class);
    }

    @ParameterizedTest(name = "U+{0} inside a reference is rejected by the CHECK (23514)")
    @ValueSource(strings = {"0085", "009F", "0001", "001F", "007F"})
    @DisplayName("a control character written past the domain is rejected by the CHECK itself, "
            + "including the upper range that [[:cntrl:]] misses")
    void shouldRejectRowWhenExternalRefContainsControlCharacter(String hex) {
        // Given
        var reference = "AB" + Character.toString(Integer.parseInt(hex, 16)) + "CD";

        // When
        var thrown = catchThrowable(() -> insertRaw(reference));

        // Then
        assertThat(thrown).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(sqlStateOf(thrown)).isEqualTo(CHECK_VIOLATION);
        assertThat(thrown).hasMessageContaining("ck_submissions_external_ref_no_control_chars");
        assertThat(rowCount()).isZero();
    }

    @ParameterizedTest(name = "U+{0} inside a reference is accepted by the CHECK")
    @ValueSource(strings = {"0020", "00A0"})
    @DisplayName("SPACE and NO-BREAK SPACE are not control characters and pass the CHECK, so it "
            + "is not one code point too wide")
    void shouldAcceptRowWhenExternalRefContainsCharacterOutsideCc(String hex) {
        // Given
        var reference = "AB" + Character.toString(Integer.parseInt(hex, 16)) + "CD";

        // When
        insertRaw(reference);

        // Then
        assertThat(jdbcTemplate.queryForObject(
                "SELECT external_ref FROM submissions", String.class)).isEqualTo(reference);
    }

    @Test
    @DisplayName("U+0000 is rejected too — but by the driver/type, NOT by the CHECK")
    void shouldRejectRowWhenExternalRefContainsNulButNotByTheCheck() {
        // Given: a text column cannot hold U+0000 at all, so PgJDBC refuses the parameter before
        // the statement executes. That is why the CHECK's class starts at \x01, not \x00 — and
        // why this test asserts only that SOME exception occurs and that it is not 23514. It
        // documents the gap the CHECK deliberately leaves to the type; it does not pin the CHECK.
        var reference = "AB\u0000CD";

        // When
        var thrown = catchThrowable(() -> insertRaw(reference));

        // Then
        assertThat(thrown).isNotNull();
        assertThat(sqlStateOf(thrown)).isNotEqualTo(CHECK_VIOLATION);
        assertThat(rowCount()).isZero();
    }
}
