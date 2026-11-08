package com.gs5.underwriting.submission.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import com.gs5.underwriting.submission.domain.SubmissionFilter.MatchesNothing;
import com.gs5.underwriting.submission.domain.SubmissionFilter.Narrowing;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The search filter, which reuses {@link SubmissionNumber#parse} and folds its three failures into a
 * search meaning: empty is not supplied (BR-31), a control character matches nothing (BR-30), more
 * than 64 characters matches nothing (BR-32). Spaces are compared exactly (BR-07), and nothing is
 * ever refused: search has one refusal reason, and it is decided at the edge.
 *
 * <p>Results are compared by value against a non-null expectation, so a {@code null} result cannot
 * pass a comparison.
 */
class SubmissionFilterTest {

    @Test
    @DisplayName("SC-47: a number filter of 65 characters matches nothing, because no number that long "
            + "can have been registered, and it is never shortened to make it match (BR-32)")
    void shouldMatchNothingWhenReferenceIsLongerThanMaximum() {
        assertThat(parseReference("A".repeat(65))).isEqualTo(new MatchesNothing());
    }

    @Test
    @DisplayName("SC-47: a number filter of exactly 64 characters is compared normally, not matched to "
            + "nothing")
    void shouldCompareNormallyWhenReferenceIsExactlyMaximum() {
        var filter = parseReference("A".repeat(64));

        assertThat(filter).isInstanceOf(Narrowing.class);
        assertThat(((Narrowing) filter).reference().value()).isEqualTo("A".repeat(64));
    }

    @Test
    @DisplayName("SC-47: a too-long filter that also holds a space still matches nothing")
    void shouldMatchNothingWhenReferenceIsTooLongAndHasCharacterNotAllowed() {
        assertThat(parseReference("A".repeat(64) + " ")).isEqualTo(new MatchesNothing());
    }

    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");
    private static final BigDecimal FIVE_THOUSAND = new BigDecimal("5000.00");
    private static final BigDecimal ONE_THOUSAND = new BigDecimal("1000.00");
    private static final Instant T0 = Instant.parse("2026-11-01T10:00:00Z");
    private static final Instant T4 = Instant.parse("2026-11-01T14:00:00Z");

    private static SubmissionFilter parseReference(String reference) {
        return SubmissionFilter.parse(reference, null, null, null, null);
    }

    @ParameterizedTest(name = "SC-46: a number filter containing {0} matches nothing")
    @ValueSource(strings = {"AB\tCD", "\n", "A\u0000B", "A\u0085", "REF;1", "A/B", "A.B", "A%2F",
            "   ", " ABC-1", "A B", "ŁÓDŹ", "A😀"})
    @DisplayName("SC-46: a number filter containing a character that is not allowed is an empty "
            + "success, never an error, and is never stripped to make it match (BR-30)")
    void shouldMatchNothingWhenReferenceContainsCharacterNotAllowed(String reference) {
        assertThat(parseReference(reference)).isEqualTo(new MatchesNothing());
    }

    @Test
    @DisplayName("SC-46: a character that is not allowed matches nothing even when the other criteria "
            + "are present")
    void shouldMatchNothingWhenCharacterNotAllowedAccompaniesOtherCriteria() {
        var filter = SubmissionFilter.parse("AB;CD", ONE_HUNDRED, null, null, null);

        assertThat(filter).isEqualTo(new MatchesNothing());
    }

    @ParameterizedTest(name = "SC-36: an empty number filter, {0}, narrows nothing")
    @NullSource
    @ValueSource(strings = {""})
    @DisplayName("SC-36: an absent or empty number filter is not applied, so it narrows nothing "
            + "(BR-31, BR-19)")
    void shouldNarrowNothingWhenReferenceIsAbsentOrEmpty(String reference) {
        assertThat(parseReference(reference))
                .isEqualTo(new Narrowing(null, null, null, null, null));
    }

    @Test
    @DisplayName("SC-36: an empty number does not suppress the other criteria, which apply as usual")
    void shouldApplyOtherCriteriaWhenReferenceIsEmpty() {
        var filter = SubmissionFilter.parse("", ONE_HUNDRED, null, null, null);

        assertThat(filter).isEqualTo(new Narrowing(null, ONE_HUNDRED, null, null, null));
    }

    @ParameterizedTest(name = "SC-37: number filter {0} is kept exactly")
    @ValueSource(strings = {"ABC-1", "abc-1", "Q-2026-001"})
    @DisplayName("SC-37: a number filter of allowed characters is compared exactly, letter case "
            + "included (BR-07)")
    void shouldKeepReferenceExactlyWhenAllowed(String reference) {
        var filter = parseReference(reference);

        assertThat(filter).isInstanceOf(Narrowing.class);
        assertThat(((Narrowing) filter).reference().value()).isEqualTo(reference);
    }

    @Test
    @DisplayName("SC-40: a contradictory amount range is still a narrowing, not a refusal (BR-21)")
    void shouldKeepContradictoryAmountRangeAsNarrowing() {
        var filter = SubmissionFilter.parse(null, FIVE_THOUSAND, ONE_THOUSAND, null, null);

        assertThat(filter).isEqualTo(
                new Narrowing(null, FIVE_THOUSAND, ONE_THOUSAND, null, null));
    }

    @Test
    @DisplayName("SC-40: a contradictory registration moment range is still a narrowing, not a refusal "
            + "(BR-21)")
    void shouldKeepContradictoryMomentRangeAsNarrowing() {
        var filter = SubmissionFilter.parse(null, null, null, T4, T0);

        assertThat(filter).isEqualTo(new Narrowing(null, null, null, T4, T0));
    }

    @Test
    @DisplayName("SC-38: a filter bound is not revalidated: a negative bound is a comparison value, "
            + "not a registered amount (BR-11 applies only to registration)")
    void shouldNotRevalidateNegativeBound() {
        var negative = new BigDecimal("-1");

        var filter = SubmissionFilter.parse(null, negative, null, null, null);

        assertThat(filter).isEqualTo(new Narrowing(null, negative, null, null, null));
    }

    @Test
    @DisplayName("SC-36: a search with no criteria at all narrows nothing")
    void shouldNarrowNothingWhenNoCriterionIsSupplied() {
        assertThat(SubmissionFilter.parse(null, null, null, null, null))
                .isEqualTo(new Narrowing(null, null, null, null, null));
    }
}
