package com.gs5.underwriting.submission.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The read side's records, independently of the useCase.
 *
 * <p>The page request is normalised <strong>inside</strong> {@link SearchSubmissionsQuery}, not in
 * the useCase and not in the adapter. That is deliberate: the record then cannot hold an
 * out-of-range page request at all, so the useCase, the adapter and the response body cannot
 * disagree about what was applied — and the size echoed to the caller is by construction the size
 * actually used.
 */
class SubmissionQueryRecordsTest {

    /**
     * An id containing no decimal digit at all, deliberately, and never
     * {@code UUID.randomUUID()}.
     *
     * <p>These tests assert that a printed record {@code doesNotContain("1234")}. A random UUID
     * prints 32 hex characters, so it contains "1234" by chance in roughly one run in 2,300 — a
     * test that fails a few times a year for no reason, which is how a suite stops being trusted.
     * A hex id drawn only from a-f cannot collide with a digit substring at all.
     */
    private static final UUID DIGIT_FREE_ID =
            UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

    private static final BigDecimal AMOUNT = new BigDecimal("1234.56");
    private static final Instant MOMENT = Instant.parse("2026-11-01T10:15:30Z");

    @Nested
    @DisplayName("The page request normalises itself (BR-27, page-size bound)")
    class PageRequestNormalisation {

        @ParameterizedTest(name = "[{index}] requested={0}, applied={1}")
        @CsvSource({"500, 100", "101, 100", "100, 100", "1, 1", "0, 1", "-1, 1", "-2147483648, 1"})
        @DisplayName("the page size is clamped into [1, 100] rather than refused")
        void shouldClampPageSizeWhenInstantiated(int requested, int applied) {
            // Given & When
            var query = new SearchSubmissionsQuery(null, null, null, null, null, 0, requested);

            // Then
            assertThat(query.size()).isEqualTo(applied);
        }

        @ParameterizedTest(name = "[{index}] requested={0}, applied={1}")
        @CsvSource({"0, 0", "1, 1", "7, 7", "-1, 0", "-99, 0", "-2147483648, 0"})
        @DisplayName("the page number is clamped to zero or above, and zero is the first page")
        void shouldClampPageNumberWhenNegativeProvided(int requested, int applied) {
            // Given & When
            var query = new SearchSubmissionsQuery(null, null, null, null, null, requested, 10);

            // Then
            assertThat(query.page()).isEqualTo(applied);
        }

        @Test
        @DisplayName("the bound is 100 and the floor is 1, as the design records them")
        void shouldDeclarePageBoundConstantsWhenInspected() {
            // Given & When & Then
            assertThat(SearchSubmissionsQuery.MIN_PAGE_SIZE).isEqualTo(1);
            assertThat(SearchSubmissionsQuery.MAX_PAGE_SIZE).isEqualTo(100);
        }

        @Test
        @DisplayName("the filters themselves are untouched by normalisation")
        void shouldPreserveFiltersUntouchedWhenNormalisingPageParameters() {
            // Given & When
            var query = new SearchSubmissionsQuery("S-1", new BigDecimal("-1"),
                    new BigDecimal("2.5"), MOMENT, MOMENT, 0, 500);

            // Then
            assertThat(query.externalRef()).isEqualTo("S-1");
            assertThat(query.insuredLimitFrom()).isEqualTo(new BigDecimal("-1"));
            assertThat(query.insuredLimitTo()).isEqualTo(new BigDecimal("2.5"));
            assertThat(query.registeredFrom()).isEqualTo(MOMENT);
            assertThat(query.registeredTo()).isEqualTo(MOMENT);
        }
    }

    @Nested
    @DisplayName("The read side does not disclose the amount in a log (BR-22)")
    class AmountIsMasked {

        @Test
        @DisplayName("a result row does not disclose the insured limit when logged, although it "
                + "still carries it for the caller")
        void shouldMaskInsuredLimitInToStringWhenSummaryViewPrinted() {
            // Given
            var view = new SubmissionSummaryView(DIGIT_FREE_ID, "S-1", AMOUNT, "uw-01", MOMENT);

            // When
            var printed = view.toString();

            // Then
            assertThat(printed).doesNotContain("1234.56", "1234", "234.56");
            assertThat(view.insuredLimit()).isEqualTo(AMOUNT);
        }

        @Test
        @DisplayName("a result row does not disclose the registering caller when logged (OPEN-01)")
        void shouldMaskRegisteringCallerInToStringWhenSummaryViewPrinted() {
            // Given
            var view = new SubmissionSummaryView(DIGIT_FREE_ID, "S-1", AMOUNT, "uw-01", MOMENT);

            // When
            var printed = view.toString();

            // Then
            assertThat(printed).doesNotContain("uw-01");
            assertThat(view.registeredBy()).isEqualTo("uw-01");
        }

        @Test
        @DisplayName("a result row prints the reference in full, because audit needs it (BR-23)")
        void shouldPrintReferenceInFullWhenSummaryViewPrinted() {
            // Given
            var view = new SubmissionSummaryView(DIGIT_FREE_ID, "S-1", AMOUNT, "uw-01", MOMENT);

            // When & Then
            assertThat(view.toString()).contains("S-1");
        }

        @Test
        @DisplayName("a logged search does not disclose its insured limit bounds: a logged search "
                + "for one exact amount that returns one row would disclose that row's amount")
        void shouldMaskAmountBoundsWhenSearchSubmissionsQueryPrinted() {
            // Given
            var query = new SearchSubmissionsQuery("S-1", AMOUNT, AMOUNT, MOMENT, MOMENT, 0, 10);

            // When & Then
            assertThat(query.toString())
                    .doesNotContain("1234.56", "1234")
                    .contains("S-1");
        }
    }

    @Nested
    @DisplayName("A page is immutable once built")
    class PageIsImmutable {

        @Test
        @DisplayName("a page copies the rows it is given, so a later change to the caller's list "
                + "cannot alter what was returned")
        void shouldDefensivelyCopyRowsWhenSubmissionPageViewConstructed() {
            // Given
            var rows = new ArrayList<SubmissionSummaryView>();
            rows.add(new SubmissionSummaryView(DIGIT_FREE_ID, "S-1", AMOUNT, "uw-01", MOMENT));
            var pageView = new SubmissionPageView(rows, 1L, 0, 10);

            // When
            rows.clear();

            // Then
            assertThat(pageView.submissions()).hasSize(1);
        }

        @Test
        @DisplayName("an empty page still reports the total match count of the whole matching set")
        void shouldReportTotalMatchCountWhenPageIsEmpty() {
            // Given & When
            var pageView = new SubmissionPageView(List.of(), 42L, 9, 10);

            // Then
            assertThat(pageView.submissions()).isEmpty();
            assertThat(pageView.totalMatchCount()).isEqualTo(42L);
            assertThat(pageView.page()).isEqualTo(9);
            assertThat(pageView.size()).isEqualTo(10);
        }
    }
}
