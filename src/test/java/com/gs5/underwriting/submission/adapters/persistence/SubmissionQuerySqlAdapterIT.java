package com.gs5.underwriting.submission.adapters.persistence;

import com.gs5.underwriting.submission.query.SubmissionSearchCriteria;
import com.gs5.underwriting.submission.query.SubmissionSummaryView;
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
        void shouldBindSuccessfullyWhenAllFiltersAreNull() {
            // Given
            var criteria = ownSubmissions();

            // When
            var page = adapter.findPage(criteria, 100, 0);
            var totalCount = adapter.countMatching(criteria);

            // Then
            assertThat(page).hasSize(5);
            assertThat(totalCount).isEqualTo(5L);
        }

        @Test
        @DisplayName("every filter supplied at once binds, so each cast is correct for its own "
                + "column type: VARCHAR, NUMERIC and TIMESTAMPTZ")
        void shouldBindSuccessfullyWhenAllFiltersAreSupplied() {
            // Given
            var criteria = new SubmissionSearchCriteria(TENANT_A, "S-3",
                    new BigDecimal("100.00"), new BigDecimal("500.00"), at(0), at(4));

            // When
            var pageReferences = referencesOnPage(criteria, 100, 0);
            var totalCount = adapter.countMatching(criteria);

            // Then
            assertThat(pageReferences).containsExactly("S-3");
            assertThat(totalCount).isEqualTo(1L);
        }

        @Test
        @DisplayName("a filter bound of a different scale than the column still compares "
                + "numerically, because the cast is to unconstrained NUMERIC")
        void shouldCompareNumericallyWhenFilterBoundHasDifferentScale() {
            // Given
            // BR-11 and BR-12 are rules about what may be REGISTERED; a comparison value may
            // carry any scale, so "250" and "250.000" must behave identically here.
            var wholeNumber = new SubmissionSearchCriteria(TENANT_A, null,
                    new BigDecimal("250"), null, null, null);
            var manyDecimals = new SubmissionSearchCriteria(TENANT_A, null,
                    new BigDecimal("250.000"), null, null, null);

            // When
            var countWholeNumber = adapter.countMatching(wholeNumber);
            var countManyDecimals = adapter.countMatching(manyDecimals);

            // Then
            assertThat(countWholeNumber).isEqualTo(3L);
            assertThat(countManyDecimals).isEqualTo(3L);
        }

        @Test
        @DisplayName("id AS submission_id binds the projection record, so the identity comes back "
                + "populated rather than null")
        void shouldBindProjectionRecordWithAliasWhenQuerying() {
            // Given
            // Without the alias, Spring Data JDBC has no column named submission_id to bind the
            // record component to. Every other field would still populate, which is what makes
            // this failure quiet enough to need its own test.
            var criteria = ownSubmissions();

            // When
            var page = adapter.findPage(criteria, 100, 0);

            // Then
            assertThat(page).allSatisfy(view ->
                    assertThat(view.submissionId()).isNotNull());
            assertThat(page).extracting(SubmissionSummaryView::submissionId)
                    .containsExactly(uuid(5), uuid(4), uuid(3), uuid(2), uuid(1));
        }

        @Test
        @DisplayName("every other projected column binds too, with the amount's scale intact")
        void shouldBindEveryProjectedColumnWhenQuerying() {
            // Given
            var criteria = new SubmissionSearchCriteria(TENANT_A, "S-3",
                    null, null, null, null);

            // When
            var page = adapter.findPage(criteria, 100, 0);

            // Then
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
        void shouldExcludeOtherOrganisationsRowFromPage() {
            // Given
            var criteria = ownSubmissions();

            // When
            var pageReferences = referencesOnPage(criteria, 100, 0);

            // Then
            assertThat(pageReferences)
                    .containsExactly("S-5", "S-4", "S-3", "S-2", "S-1");
        }

        @Test
        @DisplayName("another organisation's row is absent from the total count too: the two "
                + "statements carry the tenant predicate independently, so both are tested")
        void shouldExcludeOtherOrganisationsRowFromTotalCount() {
            // Given
            // The count is a separate statement with its own WHERE clause. A tenant predicate
            // dropped from only one of the two would leak a number while the page looked right.
            var criteria = ownSubmissions();

            // When
            var ownCount = adapter.countMatching(criteria);
            var rawDatabaseCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM submissions", Long.class);

            // Then
            assertThat(ownCount).isEqualTo(5L);
            assertThat(rawDatabaseCount).isEqualTo(6L);
        }

        @Test
        @DisplayName("a reference only another organisation holds returns nothing and counts zero, "
                + "never anything that distinguishes it from a reference nobody holds")
        void shouldReturnEmptyAndZeroCountWhenReferenceBelongsToAnotherOrganisation() {
            // Given
            insertRow(TENANT_B, uuid(8), "ONLY-IN-B", "999.00", at(6));

            var existsElsewhere = new SubmissionSearchCriteria(TENANT_A, "ONLY-IN-B",
                    null, null, null, null);
            var existsNowhere = new SubmissionSearchCriteria(TENANT_A, "NOWHERE",
                    null, null, null, null);

            // When
            var pageElsewhere = adapter.findPage(existsElsewhere, 100, 0);
            var pageNowhere = adapter.findPage(existsNowhere, 100, 0);
            var countElsewhere = adapter.countMatching(existsElsewhere);
            var countNowhere = adapter.countMatching(existsNowhere);

            // Then
            assertThat(pageElsewhere)
                    .isEqualTo(pageNowhere)
                    .isEmpty();
            assertThat(countElsewhere)
                    .isEqualTo(countNowhere)
                    .isEqualTo(0L);
        }

        @Test
        @DisplayName("each organisation resolves a shared reference to its own row only")
        void shouldResolveSharedReferenceToOwnOrganisationRowOnly() {
            // Given
            var ownS1 = new SubmissionSearchCriteria(TENANT_A, "S-1", null, null, null, null);

            // When
            var page = adapter.findPage(ownS1, 100, 0);

            // Then
            assertThat(page)
                    .extracting(SubmissionSummaryView::submissionId)
                    .containsExactly(uuid(1));
        }
    }

    @Nested
    @DisplayName("Range bounds are inclusive at both ends")
    class InclusiveBounds {

        @Test
        @DisplayName("the insured limit range includes both of its bounds")
        void shouldIncludeAmountBoundsWhenFilteringByInsuredLimitRange() {
            // Given
            var criteria = new SubmissionSearchCriteria(TENANT_A, null,
                    new BigDecimal("200.00"), new BigDecimal("400.00"), null, null);

            // When
            var pageReferences = referencesOnPage(criteria, 100, 0);
            var totalCount = adapter.countMatching(criteria);

            // Then
            assertThat(pageReferences).containsExactly("S-4", "S-3", "S-2");
            assertThat(totalCount).isEqualTo(3L);
        }

        @Test
        @DisplayName("an insured limit range of one exact amount returns that one submission")
        void shouldIncludeSingleAmountWhenInsuredLimitBoundsAreEqual() {
            // Given
            var criteria = new SubmissionSearchCriteria(TENANT_A, null,
                    new BigDecimal("300.00"), new BigDecimal("300.00"), null, null);

            // When
            var pageReferences = referencesOnPage(criteria, 100, 0);

            // Then
            assertThat(pageReferences).containsExactly("S-3");
        }

        @Test
        @DisplayName("the registration moment range includes both of its bounds")
        void shouldIncludeMomentBoundsWhenFilteringByRegisteredAtRange() {
            // Given
            var criteria = new SubmissionSearchCriteria(TENANT_A, null, null, null,
                    at(1), at(3));

            // When
            var pageReferences = referencesOnPage(criteria, 100, 0);
            var totalCount = adapter.countMatching(criteria);

            // Then
            assertThat(pageReferences).containsExactly("S-4", "S-3", "S-2");
            assertThat(totalCount).isEqualTo(3L);
        }

        @Test
        @DisplayName("a registration moment range of one exact instant returns that submission, "
                + "which is also the sharpest test that TIMESTAMPTZ round-trips the value")
        void shouldIncludeSingleMomentWhenRegisteredAtBoundsAreEqual() {
            // Given
            var criteria = new SubmissionSearchCriteria(TENANT_A, null, null, null,
                    at(2), at(2));

            // When
            var pageReferences = referencesOnPage(criteria, 100, 0);

            // Then
            assertThat(pageReferences).containsExactly("S-3");
        }

        @Test
        @DisplayName("a contradictory range matches nothing and is not an error at the SQL level "
                + "either: the predicate is simply unsatisfiable (BR-21)")
        void shouldReturnEmptyPageAndZeroCountWhenRangeBoundsAreContradictory() {
            // Given
            var amount = new SubmissionSearchCriteria(TENANT_A, null,
                    new BigDecimal("5000.00"), new BigDecimal("1000.00"), null, null);
            var moment = new SubmissionSearchCriteria(TENANT_A, null, null, null, at(4), at(0));

            // When
            var pageAmount = adapter.findPage(amount, 100, 0);
            var countAmount = adapter.countMatching(amount);
            var pageMoment = adapter.findPage(moment, 100, 0);
            var countMoment = adapter.countMatching(moment);

            // Then
            assertThat(pageAmount).isEmpty();
            assertThat(countAmount).isEqualTo(0L);
            assertThat(pageMoment).isEmpty();
            assertThat(countMoment).isEqualTo(0L);
        }
    }

    @Nested
    @DisplayName("Paging over the real planner (BR-24, BR-26, BR-27, BR-28)")
    class Paging {

        @Test
        @DisplayName("rows come back newest first")
        void shouldReturnRowsInNewestFirstOrder() {
            // Given
            var criteria = ownSubmissions();

            // When
            var pageReferences = referencesOnPage(criteria, 100, 0);

            // Then
            assertThat(pageReferences)
                    .containsExactly("S-5", "S-4", "S-3", "S-2", "S-1");
        }

        @Test
        @DisplayName("LIMIT and OFFSET are bound parameters that actually page")
        void shouldPageResultsWhenLimitAndOffsetAreProvided() {
            // Given
            var criteria = ownSubmissions();

            // When
            var firstPage = referencesOnPage(criteria, 2, 0);
            var secondPage = referencesOnPage(criteria, 2, 2);
            var thirdPage = referencesOnPage(criteria, 2, 4);

            // Then
            assertThat(firstPage).containsExactly("S-5", "S-4");
            assertThat(secondPage).containsExactly("S-3", "S-2");
            assertThat(thirdPage).containsExactly("S-1");
        }

        @Test
        @DisplayName("an offset past the end returns no rows while the count stays true (BR-27)")
        void shouldReturnEmptyPageWhilePreservingTotalCountWhenOffsetIsPastTheEnd() {
            // Given
            var criteria = ownSubmissions();

            // When
            var page = adapter.findPage(criteria, 2, 20);
            var totalCount = adapter.countMatching(criteria);

            // Then
            assertThat(page).isEmpty();
            assertThat(totalCount).isEqualTo(5L);
        }

        @Test
        @DisplayName("the count ignores limit and offset entirely (BR-26)")
        void shouldIgnoreLimitAndOffsetWhenCountingTotalMatches() {
            // Given
            // countMatching takes no limit or offset at all, which is the point: BR-26 counts the
            // whole matching set. This pins that the two statements share one predicate.
            var criteria = ownSubmissions();

            // When
            var initialCount = adapter.countMatching(criteria);
            var paged = adapter.findPage(criteria, 1, 3);
            var subsequentCount = adapter.countMatching(criteria);

            // Then
            assertThat(initialCount).isEqualTo(5L);
            assertThat(paged).hasSize(1);
            assertThat(subsequentCount).isEqualTo(5L);
        }

        @Test
        @DisplayName("rows sharing a registration moment page with no row skipped and none "
                + "repeated, which registered_at DESC alone cannot guarantee (BR-28)")
        void shouldPageDeterministicWithoutSkipOrRepeatWhenRegistrationMomentsAreTied() {
            // Given
            // "Newest first" is not a total order. Three rows now share at(2), and under
            // LIMIT/OFFSET over a non-total order PostgreSQL may return a tied row on two
            // different pages or on neither. The trailing id DESC is what makes it deterministic.
            insertRow(TENANT_A, uuid(6), "S-3A", "310.00", at(2));
            insertRow(TENANT_A, uuid(7), "S-3B", "320.00", at(2));

            // When
            var wholeSet = referencesOnPage(ownSubmissions(), 100, 0);
            var pagedByTwo = Stream.of(0L, 2L, 4L, 6L)
                    .flatMap(offset -> referencesOnPage(ownSubmissions(), 2, offset).stream())
                    .toList();
            var totalCount = adapter.countMatching(ownSubmissions());

            // Then
            assertThat(wholeSet).hasSize(7);
            assertThat(pagedByTwo).doesNotHaveDuplicates().isEqualTo(wholeSet);
            assertThat(totalCount).isEqualTo(7L);
        }

        @Test
        @DisplayName("the tie-break is id DESC, so the order of tied rows is defined rather than "
                + "merely stable by luck")
        void shouldOrderTiedRowsByIdDescending() {
            // Given
            insertRow(TENANT_A, uuid(6), "S-3A", "310.00", at(2));
            insertRow(TENANT_A, uuid(7), "S-3B", "320.00", at(2));
            var tied = new SubmissionSearchCriteria(TENANT_A, null, null, null, at(2), at(2));

            // When
            var page = adapter.findPage(tied, 100, 0);

            // Then
            assertThat(page)
                    .extracting(SubmissionSummaryView::submissionId)
                    .containsExactly(uuid(7), uuid(6), uuid(3));
        }
    }
}
