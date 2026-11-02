package com.example.service.submission.query;

import com.example.service.shared.kernel.ActorContext;
import com.example.service.shared.kernel.ActorRole;
import com.example.service.shared.kernel.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Searching submissions: BR-16 to BR-28, and the single refusal reason Search has.
 *
 * <p>The shape of this class is the point of the specification: almost every test asserts a
 * <em>success</em>. Matching nothing, a contradictory range and a page past the end of the
 * matching set are all successful empty pages, and a role and another organisation's data never
 * produce a refusal either. There is exactly one way to be refused, and it is in the first
 * nested class.
 */
class SubmissionQueryFacadeTest {

    private static final UUID TENANT_A = UUID.fromString("0a000000-0000-0000-0000-00000000000a");
    private static final UUID TENANT_B = UUID.fromString("0b000000-0000-0000-0000-00000000000b");

    private static final Instant T0 = Instant.parse("2026-11-01T10:00:00Z");

    private static final ActorContext VIEWER_OF_A =
            new ActorContext(TENANT_A, "viewer-01", ActorRole.VIEWER);

    private InMemorySubmissionQueryRepository repository;
    private SubmissionQueryFacade facade;

    /** Own organisation, oldest first in declaration order; newest-first order is s5..s1. */
    private SubmissionSummaryView s1;
    private SubmissionSummaryView s2;
    private SubmissionSummaryView s3;
    private SubmissionSummaryView s4;
    private SubmissionSummaryView s5;

    private static Instant at(int hours) {
        return T0.plus(Duration.ofHours(hours));
    }

    @BeforeEach
    void setUp() {
        repository = new InMemorySubmissionQueryRepository();
        facade = new SubmissionQueryFacade(repository);

        s1 = repository.givenSubmission(TENANT_A, uuid(1), "S-1", "100.00", at(0));
        s2 = repository.givenSubmission(TENANT_A, uuid(2), "S-2", "200.00", at(1));
        s3 = repository.givenSubmission(TENANT_A, uuid(3), "S-3", "300.00", at(2));
        s4 = repository.givenSubmission(TENANT_A, uuid(4), "S-4", "400.00", at(3));
        s5 = repository.givenSubmission(TENANT_A, uuid(5), "S-5", "500.00", at(4));

        // Another organisation's submission, newest of all, matching every filter below. It must
        // be invisible: absent from the page AND from the total match count.
        repository.givenSubmission(TENANT_B, uuid(9), "S-1", "100.00", at(5));
    }

    private static UUID uuid(int n) {
        return UUID.fromString("00000000-0000-0000-0000-00000000000" + n);
    }

    private Result<SubmissionPageView, SearchSubmissionsError> searchAs(ActorContext actor,
                                                                        SearchSubmissionsQuery q) {
        return ActorContext.callWith(actor, () -> facade.search(q));
    }

    private static SearchSubmissionsQuery unfiltered(int page, int size) {
        return new SearchSubmissionsQuery(null, null, null, null, null, page, size);
    }

    private static SearchSubmissionsQuery byReference(String externalRef) {
        return new SearchSubmissionsQuery(externalRef, null, null, null, null, 0, 100);
    }

    private static SearchSubmissionsQuery byAmount(String from, String to) {
        return new SearchSubmissionsQuery(null,
                from == null ? null : new BigDecimal(from),
                to == null ? null : new BigDecimal(to),
                null, null, 0, 100);
    }

    private static SearchSubmissionsQuery byMoment(Instant from, Instant to) {
        return new SearchSubmissionsQuery(null, null, null, from, to, 0, 100);
    }

    private static Result<SubmissionPageView, SearchSubmissionsError> page(
            List<SubmissionSummaryView> rows, long totalMatchCount, int page, int size) {
        return Result.success(new SubmissionPageView(rows, totalMatchCount, page, size));
    }

    // =================================================================== BR-01

    @Nested
    @DisplayName("The one and only reason a search is refused (BR-01)")
    class TheOnlyRefusal {

        @Test
        @DisplayName("a search is refused when no caller is known")
        void unidentified_caller_is_refused() {
            var result = facade.search(unfiltered(0, 100));

            assertThat(result).isEqualTo(
                    Result.<SubmissionPageView, SearchSubmissionsError>failure(
                            new SearchSubmissionsError.CallerNotIdentified()));
        }

        @Test
        @DisplayName("search has exactly one refusal reason, which is deliberate and complete")
        void search_has_exactly_one_refusal_reason() {
            var variantNames = Arrays.stream(
                            SearchSubmissionsError.class.getPermittedSubclasses())
                    .map(Class::getSimpleName)
                    .toList();

            assertThat(variantNames).containsExactly("CallerNotIdentified");
        }
    }

    // =================================================================== BR-16

    @Nested
    @DisplayName("Every role may search, and the role changes nothing (BR-16)")
    class RoleIsIrrelevant {

        @ParameterizedTest
        @EnumSource(ActorRole.class)
        @DisplayName("every role — viewer included — gets the identical page and count")
        void every_role_gets_the_same_answer(ActorRole role) {
            var actor = new ActorContext(TENANT_A, "actor-" + role, role);

            var result = searchAs(actor, unfiltered(0, 100));

            assertThat(result).isEqualTo(page(List.of(s5, s4, s3, s2, s1), 5L, 0, 100));
        }

        @Test
        @DisplayName("a viewer, who may not register at all, searches without restriction")
        void viewer_searches_without_restriction() {
            var result = searchAs(VIEWER_OF_A, byAmount("200.00", "400.00"));

            assertThat(result).isEqualTo(page(List.of(s4, s3, s2), 3L, 0, 100));
        }
    }

    // =================================================================== BR-17

    @Nested
    @DisplayName("Only the caller's own organisation is visible (BR-17)")
    class TenantIsolation {

        @Test
        @DisplayName("another organisation's submission is absent from the page")
        void other_organisation_is_absent_from_the_page() {
            var result = searchAs(VIEWER_OF_A, unfiltered(0, 100));

            assertThat(result).isEqualTo(page(List.of(s5, s4, s3, s2, s1), 5L, 0, 100));
        }

        @Test
        @DisplayName("another organisation's submission is absent from the total match count too, "
                + "so its existence is not revealed by a number")
        void other_organisation_is_absent_from_the_count() {
            var result = searchAs(VIEWER_OF_A, unfiltered(0, 100));

            assertThat(result.toOptional()).hasValueSatisfying(view ->
                    assertThat(view.totalMatchCount()).isEqualTo(5L));
        }

        @Test
        @DisplayName("asking by a reference only another organisation holds is an empty success, "
                + "never a refusal: there is no forbidden outcome anywhere")
        void other_organisations_reference_is_invisible_not_forbidden() {
            repository.givenSubmission(TENANT_B, uuid(8), "ONLY-IN-B", "999.00", at(6));

            var result = searchAs(VIEWER_OF_A, byReference("ONLY-IN-B"));

            assertThat(result).isEqualTo(page(List.of(), 0L, 0, 100));
        }

        @Test
        @DisplayName("each organisation sees only its own submission under a shared reference")
        void a_shared_reference_resolves_per_organisation() {
            var ownOnly = searchAs(VIEWER_OF_A, byReference("S-1"));

            assertThat(ownOnly).isEqualTo(page(List.of(s1), 1L, 0, 100));
        }
    }

    // ========================================================== BR-18, BR-19

    @Nested
    @DisplayName("Filters narrow together, and an omitted filter narrows nothing (BR-18, BR-19)")
    class Filters {

        @Test
        @DisplayName("a search with no criteria returns every submission of the own organisation")
        void no_criteria_returns_everything_own() {
            var result = searchAs(VIEWER_OF_A, unfiltered(0, 100));

            assertThat(result).isEqualTo(page(List.of(s5, s4, s3, s2, s1), 5L, 0, 100));
        }

        @Test
        @DisplayName("the reference filter narrows to at most one submission")
        void reference_filter_narrows_to_one() {
            var result = searchAs(VIEWER_OF_A, byReference("S-3"));

            assertThat(result).isEqualTo(page(List.of(s3), 1L, 0, 100));
        }

        @ParameterizedTest
        @ValueSource(strings = {"s-3", "S-3 ", " S-3", "S3"})
        @DisplayName("the reference filter compares exactly as supplied: no case folding and no "
                + "trimming, so a near-miss is an empty success (BR-07)")
        void reference_filter_is_exact(String nearMiss) {
            var result = searchAs(VIEWER_OF_A, byReference(nearMiss));

            assertThat(result).isEqualTo(page(List.of(), 0L, 0, 100));
        }

        @Test
        @DisplayName("both insured limit bounds are inclusive")
        void amount_bounds_are_inclusive() {
            var result = searchAs(VIEWER_OF_A, byAmount("200.00", "400.00"));

            assertThat(result).isEqualTo(page(List.of(s4, s3, s2), 3L, 0, 100));
        }

        @Test
        @DisplayName("both registration moment bounds are inclusive")
        void moment_bounds_are_inclusive() {
            var result = searchAs(VIEWER_OF_A, byMoment(at(1), at(3)));

            assertThat(result).isEqualTo(page(List.of(s4, s3, s2), 3L, 0, 100));
        }

        @Test
        @DisplayName("two criteria narrow together, not separately")
        void two_criteria_are_combined_with_and() {
            var query = new SearchSubmissionsQuery(null, new BigDecimal("200.00"), null,
                    null, at(2), 0, 100);

            var result = searchAs(VIEWER_OF_A, query);

            assertThat(result).isEqualTo(page(List.of(s3, s2), 2L, 0, 100));
        }

        @Test
        @DisplayName("a filter bound may be negative, zero or carry any scale: BR-11 and BR-12 are "
                + "rules about what may be REGISTERED and are not re-imposed on a comparison value")
        void filter_bounds_are_not_revalidated() {
            var query = new SearchSubmissionsQuery(null, new BigDecimal("-1"),
                    new BigDecimal("99999.5"), null, null, 0, 100);

            var result = searchAs(VIEWER_OF_A, query);

            assertThat(result).isEqualTo(page(List.of(s5, s4, s3, s2, s1), 5L, 0, 100));
        }
    }

    // ================================================================== BR-20

    @Nested
    @DisplayName("Matching nothing is a success (BR-20)")
    class MatchingNothing {

        @Test
        @DisplayName("a search that matches no submission succeeds with an empty page and a count "
                + "of zero")
        void matching_nothing_is_an_empty_success() {
            var result = searchAs(VIEWER_OF_A, byReference("NOT-A-REFERENCE"));

            assertThat(result).isEqualTo(page(List.of(), 0L, 0, 100));
        }
    }

    // ================================================================== BR-21

    @Nested
    @DisplayName("A contradictory range matches nothing and is still a success (BR-21)")
    class ContradictoryRanges {

        @Test
        @DisplayName("an insured limit range whose lower bound exceeds its upper bound is a "
                + "SUCCESSFUL empty page with a total match count of zero — not a refusal")
        void contradictory_amount_range_is_an_empty_success() {
            var result = searchAs(VIEWER_OF_A, byAmount("5000.00", "1000.00"));

            assertThat(result).isEqualTo(page(List.of(), 0L, 0, 100));
        }

        @Test
        @DisplayName("a registration moment range whose lower bound is after its upper bound is "
                + "identically a SUCCESSFUL empty page with a count of zero — not a refusal")
        void contradictory_moment_range_is_an_empty_success() {
            var result = searchAs(VIEWER_OF_A, byMoment(at(4), at(0)));

            assertThat(result).isEqualTo(page(List.of(), 0L, 0, 100));
        }

        @Test
        @DisplayName("both ranges contradicting at once is still one empty success")
        void both_ranges_contradicting_is_still_a_success() {
            var query = new SearchSubmissionsQuery(null, new BigDecimal("5000.00"),
                    new BigDecimal("1000.00"), at(4), at(0), 0, 100);

            var result = searchAs(VIEWER_OF_A, query);

            assertThat(result).isEqualTo(page(List.of(), 0L, 0, 100));
        }
    }

    // ================================================================== BR-24

    @Nested
    @DisplayName("Results are newest first (BR-24)")
    class Order {

        @Test
        @DisplayName("submissions come back ordered by registration moment, newest first")
        void newest_first() {
            var result = searchAs(VIEWER_OF_A, unfiltered(0, 100));

            assertThat(result.toOptional()).hasValueSatisfying(view ->
                    assertThat(view.submissions())
                            .extracting(SubmissionSummaryView::externalRef)
                            .containsExactly("S-5", "S-4", "S-3", "S-2", "S-1"));
        }
    }

    // ============================================= BR-25, BR-26, BR-27, BR-28

    @Nested
    @DisplayName("Paging (BR-25 to BR-28, and the page-size bound)")
    class Paging {

        @Test
        @DisplayName("paging is zero-based: page 0 is the FIRST page, not the second")
        void paging_is_zero_based() {
            var result = searchAs(VIEWER_OF_A, unfiltered(0, 2));

            assertThat(result).isEqualTo(page(List.of(s5, s4), 5L, 0, 2));
        }

        @Test
        @DisplayName("page 1 is the second page")
        void page_one_is_the_second_page() {
            var result = searchAs(VIEWER_OF_A, unfiltered(1, 2));

            assertThat(result).isEqualTo(page(List.of(s3, s2), 5L, 1, 2));
        }

        @Test
        @DisplayName("an oversized page request is clamped, never refused, and the answer echoes "
                + "the size that was APPLIED — not the size that was asked for")
        void oversized_size_is_clamped_and_echoed() {
            // A caller who asked for 500, got 100 rows, and then asked for page 1 size 500 would
            // skip rows 100-499 and break BR-28. Echoing the applied size is what prevents that,
            // so this is a correctness condition, not a nicety.
            var result = searchAs(VIEWER_OF_A, unfiltered(0, 500));

            assertThat(result).isEqualTo(page(List.of(s5, s4, s3, s2, s1), 5L, 0, 100));
        }

        @ParameterizedTest
        @ValueSource(ints = {0, -1, -500, Integer.MIN_VALUE})
        @DisplayName("a degenerate page size clamps up to one row and still succeeds: search has "
                + "exactly one refusal reason, so a malformed page request cannot be refused")
        void degenerate_size_clamps_up_to_one(int requestedSize) {
            var result = searchAs(VIEWER_OF_A, unfiltered(0, requestedSize));

            assertThat(result).isEqualTo(page(List.of(s5), 5L, 0, 1));
        }

        @Test
        @DisplayName("a negative page clamps to the first page and still succeeds")
        void negative_page_clamps_to_zero() {
            var result = searchAs(VIEWER_OF_A, unfiltered(-3, 2));

            assertThat(result).isEqualTo(page(List.of(s5, s4), 5L, 0, 2));
        }

        @Test
        @DisplayName("the total match count is the same on every page, and ignores which page was "
                + "asked for (BR-26)")
        void total_count_is_independent_of_the_page_requested() {
            var totals = Stream.of(0, 1, 2, 7)
                    .map(p -> searchAs(VIEWER_OF_A, unfiltered(p, 2)))
                    .map(result -> result.toOptional()
                            .map(SubmissionPageView::totalMatchCount)
                            .orElse(-1L))
                    .toList();

            assertThat(totals).containsExactly(5L, 5L, 5L, 5L);
        }

        @Test
        @DisplayName("a page beyond the end of the matching set is a success carrying an empty "
                + "page and the TRUE total match count (BR-26, BR-27)")
        void page_past_the_end_is_an_empty_success_with_the_true_total() {
            var result = searchAs(VIEWER_OF_A, unfiltered(10, 2));

            assertThat(result).isEqualTo(page(List.of(), 5L, 10, 2));
        }

        @Test
        @DisplayName("a page beyond the end of an EMPTY matching set is a success with a total of "
                + "zero")
        void page_past_the_end_of_an_empty_set_is_a_success() {
            var query = new SearchSubmissionsQuery("NOT-A-REFERENCE", null, null, null, null, 4, 2);

            var result = searchAs(VIEWER_OF_A, query);

            assertThat(result).isEqualTo(page(List.of(), 0L, 4, 2));
        }

        @Test
        @DisplayName("paging reaches the whole matching set once each: nothing skipped, nothing "
                + "twice (BR-28)")
        void paging_reaches_everything_exactly_once() {
            var paged = Stream.of(0, 1, 2)
                    .map(p -> searchAs(VIEWER_OF_A, unfiltered(p, 2)))
                    .flatMap(result -> result.toOptional().stream())
                    .flatMap(view -> view.submissions().stream())
                    .toList();

            assertThat(paged).containsExactly(s5, s4, s3, s2, s1).doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("submissions registered in the identical moment still page without skipping "
                + "or repeating one, because the order is total")
        void ties_in_the_registration_moment_do_not_break_paging() {
            // "Newest first" alone is not a total order: two submissions can share a moment, and
            // under LIMIT/OFFSET a tied row can then appear on two pages or on neither.
            repository.givenSubmission(TENANT_A, uuid(6), "S-3A", "310.00", at(2));
            repository.givenSubmission(TENANT_A, uuid(7), "S-3B", "320.00", at(2));

            var wholeSet = searchAs(VIEWER_OF_A, unfiltered(0, 100));
            var pagedByThree = Stream.of(0, 1, 2)
                    .map(p -> searchAs(VIEWER_OF_A, unfiltered(p, 3)))
                    .flatMap(result -> result.toOptional().stream())
                    .flatMap(view -> view.submissions().stream())
                    .toList();

            assertThat(pagedByThree).doesNotHaveDuplicates().hasSize(7);
            assertThat(wholeSet.toOptional()).hasValueSatisfying(view ->
                    assertThat(view.submissions()).isEqualTo(pagedByThree));
        }
    }
}
