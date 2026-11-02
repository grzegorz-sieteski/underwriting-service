package com.example.service.submission.adapters.persistence;

import com.example.service.submission.query.SubmissionSearchCriteria;
import com.example.service.submission.query.SubmissionSummaryView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jdbc.test.autoconfigure.DataJdbcTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureDataSourceInitialization;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The read adapter against a real PostgreSQL, because four things in its two statements are true
 * only of a real server and an in-memory fake cannot disprove any of them:
 *
 * <ul>
 *   <li><strong>The {@code CAST(:param AS type)} wrappers.</strong> PostgreSQL cannot infer the
 *       type of a bare parameter used in {@code IS NULL} and rejects the statement with "could not
 *       determine data type of parameter". Every test here that leaves a filter null is also a test
 *       that the casts are present and correct.</li>
 *   <li><strong>{@code SELECT id AS submission_id}.</strong> Spring Data JDBC binds result columns
 *       to record components by name, so without the alias {@code submissionId} stays null or the
 *       mapping fails outright.</li>
 *   <li><strong>The tenant predicate</strong> in both statements, independently (BR-17).</li>
 *   <li><strong>{@code ORDER BY registered_at DESC, id DESC}</strong> paging a tied registration
 *       moment. {@code TIMESTAMPTZ} stores microseconds and two rows can share a value; only a
 *       real planner doing real {@code LIMIT}/{@code OFFSET} can show whether a tied row lands on
 *       two pages or on neither.</li>
 * </ul>
 *
 * <p>See {@code SubmissionCommandSqlAdapterIT} for why
 * {@code @AutoConfigureDataSourceInitialization} and {@code @Import} are both required here.
 */
@DataJdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@AutoConfigureDataSourceInitialization
@Import(SubmissionQuerySqlAdapter.class)
@Testcontainers
class SubmissionQuerySqlAdapterIT {

    /** {@code org.testcontainers.postgresql.*}, not the deprecated
     * {@code org.testcontainers.containers.*} — see {@code SubmissionCommandSqlAdapterIT}. */
    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    private static final UUID TENANT_A = UUID.fromString("0a000000-0000-0000-0000-00000000000a");
    private static final UUID TENANT_B = UUID.fromString("0b000000-0000-0000-0000-00000000000b");

    /** Whole seconds: {@code TIMESTAMPTZ} keeps microseconds and {@code Instant} nanoseconds, so
     * a value from {@code Instant.now()} would not come back equal to what went in. */
    private static final Instant T0 = Instant.parse("2026-11-01T10:00:00Z");

    @Autowired
    private SubmissionQuerySqlAdapter adapter;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static Instant at(int hours) {
        return T0.plus(Duration.ofHours(hours));
    }

    private static UUID uuid(int n) {
        return UUID.fromString("00000000-0000-0000-0000-00000000000" + n);
    }

    /** Only the tenant is a criteria field the caller cannot supply; the rest are the filters. */
    private static SubmissionSearchCriteria ownSubmissions() {
        return new SubmissionSearchCriteria(TENANT_A, null, null, null, null, null);
    }

    @BeforeEach
    void seed() {
        insertRow(TENANT_A, uuid(1), "S-1", "100.00", at(0));
        insertRow(TENANT_A, uuid(2), "S-2", "200.00", at(1));
        insertRow(TENANT_A, uuid(3), "S-3", "300.00", at(2));
        insertRow(TENANT_A, uuid(4), "S-4", "400.00", at(3));
        insertRow(TENANT_A, uuid(5), "S-5", "500.00", at(4));

        // Another organisation's row: the newest of all, and it matches every filter below.
        insertRow(TENANT_B, uuid(9), "S-1", "100.00", at(5));
    }

    private void insertRow(UUID tenantId,
                           UUID id,
                           String externalRef,
                           String insuredLimit,
                           Instant registeredAt) {
        // Inserted with plain SQL rather than through the write adapter, so a bug in the write
        // path cannot make a read test pass or fail for the wrong reason.
        jdbcTemplate.update("""
                INSERT INTO submissions
                    (id, tenant_id, external_ref, insured_limit, registered_by, registered_at,
                     version)
                VALUES (?, ?, ?, ?, ?, ?, 0)
                """, id, tenantId, externalRef, new BigDecimal(insuredLimit), "uw-01",
                java.sql.Timestamp.from(registeredAt));
    }

    private List<String> referencesOnPage(SubmissionSearchCriteria criteria, int limit, long offset) {
        return adapter.findPage(criteria, limit, offset).stream()
                .map(SubmissionSummaryView::externalRef)
                .toList();
    }

    @Nested
    @DisplayName("The statements bind at all against a real server")
    class TheStatementsBind {

        @Test
        @DisplayName("every filter left null binds, which is what the CAST(... AS type) wrappers "
                + "are for: PostgreSQL cannot infer the type of a bare parameter in IS NULL")
        void all_null_filters_bind() {
            var page = adapter.findPage(ownSubmissions(), 100, 0);

            assertThat(page).hasSize(5);
            assertThat(adapter.countMatching(ownSubmissions())).isEqualTo(5L);
        }

        @Test
        @DisplayName("every filter supplied at once binds, so each cast is correct for its own "
                + "column type: VARCHAR, NUMERIC and TIMESTAMPTZ")
        void all_filters_supplied_bind() {
            var criteria = new SubmissionSearchCriteria(TENANT_A, "S-3",
                    new BigDecimal("100.00"), new BigDecimal("500.00"), at(0), at(4));

            assertThat(referencesOnPage(criteria, 100, 0)).containsExactly("S-3");
            assertThat(adapter.countMatching(criteria)).isEqualTo(1L);
        }

        @Test
        @DisplayName("a filter bound of a different scale than the column still compares "
                + "numerically, because the cast is to unconstrained NUMERIC")
        void a_bound_of_another_scale_still_compares() {
            // BR-11 and BR-12 are rules about what may be REGISTERED; a comparison value may
            // carry any scale, so "250" and "250.000" must behave identically here.
            var wholeNumber = new SubmissionSearchCriteria(TENANT_A, null,
                    new BigDecimal("250"), null, null, null);
            var manyDecimals = new SubmissionSearchCriteria(TENANT_A, null,
                    new BigDecimal("250.000"), null, null, null);

            assertThat(adapter.countMatching(wholeNumber)).isEqualTo(3L);
            assertThat(adapter.countMatching(manyDecimals)).isEqualTo(3L);
        }

        @Test
        @DisplayName("id AS submission_id binds the projection record, so the identity comes back "
                + "populated rather than null")
        void the_alias_binds_the_projection() {
            // Without the alias, Spring Data JDBC has no column named submission_id to bind the
            // record component to. Every other field would still populate, which is what makes
            // this failure quiet enough to need its own test.
            var page = adapter.findPage(ownSubmissions(), 100, 0);

            assertThat(page).allSatisfy(view ->
                    assertThat(view.submissionId()).isNotNull());
            assertThat(page).extracting(SubmissionSummaryView::submissionId)
                    .containsExactly(uuid(5), uuid(4), uuid(3), uuid(2), uuid(1));
        }

        @Test
        @DisplayName("every other projected column binds too, with the amount's scale intact")
        void every_projected_column_binds() {
            var criteria = new SubmissionSearchCriteria(TENANT_A, "S-3",
                    null, null, null, null);

            assertThat(adapter.findPage(criteria, 100, 0)).containsExactly(
                    new SubmissionSummaryView(uuid(3), "S-3", new BigDecimal("300.00"),
                            "uw-01", at(2)));
        }
    }

    @Nested
    @DisplayName("Only the caller's own organisation is visible (BR-17)")
    class TenantIsolation {

        @Test
        @DisplayName("another organisation's row is absent from the page, although it is the "
                + "newest row in the table")
        void absent_from_the_page() {
            assertThat(referencesOnPage(ownSubmissions(), 100, 0))
                    .containsExactly("S-5", "S-4", "S-3", "S-2", "S-1");
        }

        @Test
        @DisplayName("another organisation's row is absent from the total count too: the two "
                + "statements carry the tenant predicate independently, so both are tested")
        void absent_from_the_count() {
            // The count is a separate statement with its own WHERE clause. A tenant predicate
            // dropped from only one of the two would leak a number while the page looked right.
            assertThat(adapter.countMatching(ownSubmissions())).isEqualTo(5L);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM submissions", Long.class)).isEqualTo(6L);
        }

        @Test
        @DisplayName("a reference only another organisation holds returns nothing and counts zero, "
                + "never anything that distinguishes it from a reference nobody holds")
        void another_organisations_reference_is_indistinguishable_from_absent() {
            insertRow(TENANT_B, uuid(8), "ONLY-IN-B", "999.00", at(6));

            var existsElsewhere = new SubmissionSearchCriteria(TENANT_A, "ONLY-IN-B",
                    null, null, null, null);
            var existsNowhere = new SubmissionSearchCriteria(TENANT_A, "NOWHERE",
                    null, null, null, null);

            assertThat(adapter.findPage(existsElsewhere, 100, 0))
                    .isEqualTo(adapter.findPage(existsNowhere, 100, 0))
                    .isEmpty();
            assertThat(adapter.countMatching(existsElsewhere))
                    .isEqualTo(adapter.countMatching(existsNowhere))
                    .isEqualTo(0L);
        }

        @Test
        @DisplayName("each organisation resolves a shared reference to its own row only")
        void a_shared_reference_resolves_per_organisation() {
            var ownS1 = new SubmissionSearchCriteria(TENANT_A, "S-1", null, null, null, null);

            assertThat(adapter.findPage(ownS1, 100, 0))
                    .extracting(SubmissionSummaryView::submissionId)
                    .containsExactly(uuid(1));
        }
    }

    @Nested
    @DisplayName("Range bounds are inclusive at both ends")
    class InclusiveBounds {

        @Test
        @DisplayName("the insured limit range includes both of its bounds")
        void amount_bounds_are_inclusive() {
            var criteria = new SubmissionSearchCriteria(TENANT_A, null,
                    new BigDecimal("200.00"), new BigDecimal("400.00"), null, null);

            assertThat(referencesOnPage(criteria, 100, 0)).containsExactly("S-4", "S-3", "S-2");
            assertThat(adapter.countMatching(criteria)).isEqualTo(3L);
        }

        @Test
        @DisplayName("an insured limit range of one exact amount returns that one submission")
        void a_degenerate_amount_range_is_inclusive() {
            var criteria = new SubmissionSearchCriteria(TENANT_A, null,
                    new BigDecimal("300.00"), new BigDecimal("300.00"), null, null);

            assertThat(referencesOnPage(criteria, 100, 0)).containsExactly("S-3");
        }

        @Test
        @DisplayName("the registration moment range includes both of its bounds")
        void moment_bounds_are_inclusive() {
            var criteria = new SubmissionSearchCriteria(TENANT_A, null, null, null,
                    at(1), at(3));

            assertThat(referencesOnPage(criteria, 100, 0)).containsExactly("S-4", "S-3", "S-2");
            assertThat(adapter.countMatching(criteria)).isEqualTo(3L);
        }

        @Test
        @DisplayName("a registration moment range of one exact instant returns that submission, "
                + "which is also the sharpest test that TIMESTAMPTZ round-trips the value")
        void a_degenerate_moment_range_is_inclusive() {
            var criteria = new SubmissionSearchCriteria(TENANT_A, null, null, null,
                    at(2), at(2));

            assertThat(referencesOnPage(criteria, 100, 0)).containsExactly("S-3");
        }

        @Test
        @DisplayName("a contradictory range matches nothing and is not an error at the SQL level "
                + "either: the predicate is simply unsatisfiable (BR-21)")
        void a_contradictory_range_is_simply_empty() {
            var amount = new SubmissionSearchCriteria(TENANT_A, null,
                    new BigDecimal("5000.00"), new BigDecimal("1000.00"), null, null);
            var moment = new SubmissionSearchCriteria(TENANT_A, null, null, null, at(4), at(0));

            assertThat(adapter.findPage(amount, 100, 0)).isEmpty();
            assertThat(adapter.countMatching(amount)).isEqualTo(0L);
            assertThat(adapter.findPage(moment, 100, 0)).isEmpty();
            assertThat(adapter.countMatching(moment)).isEqualTo(0L);
        }
    }

    @Nested
    @DisplayName("Paging over the real planner (BR-24, BR-26, BR-27, BR-28)")
    class Paging {

        @Test
        @DisplayName("rows come back newest first")
        void newest_first() {
            assertThat(referencesOnPage(ownSubmissions(), 100, 0))
                    .containsExactly("S-5", "S-4", "S-3", "S-2", "S-1");
        }

        @Test
        @DisplayName("LIMIT and OFFSET are bound parameters that actually page")
        void limit_and_offset_page() {
            assertThat(referencesOnPage(ownSubmissions(), 2, 0)).containsExactly("S-5", "S-4");
            assertThat(referencesOnPage(ownSubmissions(), 2, 2)).containsExactly("S-3", "S-2");
            assertThat(referencesOnPage(ownSubmissions(), 2, 4)).containsExactly("S-1");
        }

        @Test
        @DisplayName("an offset past the end returns no rows while the count stays true (BR-27)")
        void offset_past_the_end_returns_nothing_but_counts_truly() {
            assertThat(adapter.findPage(ownSubmissions(), 2, 20)).isEmpty();
            assertThat(adapter.countMatching(ownSubmissions())).isEqualTo(5L);
        }

        @Test
        @DisplayName("the count ignores limit and offset entirely (BR-26)")
        void the_count_ignores_the_page_request() {
            // countMatching takes no limit or offset at all, which is the point: BR-26 counts the
            // whole matching set. This pins that the two statements share one predicate.
            assertThat(adapter.countMatching(ownSubmissions())).isEqualTo(5L);
            assertThat(adapter.findPage(ownSubmissions(), 1, 3)).hasSize(1);
            assertThat(adapter.countMatching(ownSubmissions())).isEqualTo(5L);
        }

        @Test
        @DisplayName("rows sharing a registration moment page with no row skipped and none "
                + "repeated, which registered_at DESC alone cannot guarantee (BR-28)")
        void tied_registration_moments_page_without_skip_or_repeat() {
            // "Newest first" is not a total order. Three rows now share at(2), and under
            // LIMIT/OFFSET over a non-total order PostgreSQL may return a tied row on two
            // different pages or on neither. The trailing id DESC is what makes it deterministic.
            insertRow(TENANT_A, uuid(6), "S-3A", "310.00", at(2));
            insertRow(TENANT_A, uuid(7), "S-3B", "320.00", at(2));

            var wholeSet = referencesOnPage(ownSubmissions(), 100, 0);
            var pagedByTwo = Stream.of(0L, 2L, 4L, 6L)
                    .flatMap(offset -> referencesOnPage(ownSubmissions(), 2, offset).stream())
                    .toList();

            assertThat(wholeSet).hasSize(7);
            assertThat(pagedByTwo).doesNotHaveDuplicates().isEqualTo(wholeSet);
            assertThat(adapter.countMatching(ownSubmissions())).isEqualTo(7L);
        }

        @Test
        @DisplayName("the tie-break is id DESC, so the order of tied rows is defined rather than "
                + "merely stable by luck")
        void the_tie_break_is_id_descending() {
            insertRow(TENANT_A, uuid(6), "S-3A", "310.00", at(2));
            insertRow(TENANT_A, uuid(7), "S-3B", "320.00", at(2));

            var tied = new SubmissionSearchCriteria(TENANT_A, null, null, null, at(2), at(2));

            assertThat(adapter.findPage(tied, 100, 0))
                    .extracting(SubmissionSummaryView::submissionId)
                    .containsExactly(uuid(7), uuid(6), uuid(3));
        }
    }
}
