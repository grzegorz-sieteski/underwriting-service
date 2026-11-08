package com.gs5.underwriting.submission.domain;

import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * The filter a search is run with, in one of two shapes. {@link #parse} is total: it never fails and
 * never refuses, because search has exactly one refusal reason and it is decided at the edge.
 *
 * <p>The number filter reuses {@link SubmissionNumber#parse}, so there is no second number
 * type and no second character test. Its three failures fold into a search meaning:
 *
 * <ul>
 *   <li>{@code Missing} (empty) is <em>not supplied</em> (BR-31);</li>
 *   <li>{@code CharacterNotAllowed} is {@link MatchesNothing} (BR-30);</li>
 *   <li>{@code TooLong} is {@link MatchesNothing} (BR-32).</li>
 * </ul>
 *
 * <p>A {@link Narrowing} is safe to hand to the store, because its number is an
 * {@link SubmissionNumber}, which cannot hold a character the store cannot represent.
 */
public sealed interface SubmissionFilter {

    /**
     * Every supplied criterion can match something. A {@code null} means the criterion was not
     * supplied (BR-19), which includes an empty number filter (BR-31).
     */
    record Narrowing(@Nullable SubmissionNumber reference,
                     @Nullable BigDecimal insuredLimitFrom,
                     @Nullable BigDecimal insuredLimitTo,
                     @Nullable Instant registeredFrom,
                     @Nullable Instant registeredTo) implements SubmissionFilter {

        private static final String MASKED = "***";

        /**
         * Masks both insured-limit bounds (BR-22, strict reading): a logged search for
         * {@code from=1200.00, to=1200.00} that returns one row discloses that row's amount. The
         * number prints through its own escaping {@code toString()} (BR-23).
         */
        @Override
        public String toString() {
            return "Narrowing[submissionNumber=" + reference
                    + ", insuredLimitFrom=" + MASKED
                    + ", insuredLimitTo=" + MASKED
                    + ", registeredFrom=" + registeredFrom
                    + ", registeredTo=" + registeredTo + "]";
        }
    }

    /** BR-30, BR-32: the number filter provably matches nothing, so the store is never asked. */
    record MatchesNothing() implements SubmissionFilter {
    }

    /**
     * Total. Calls {@link SubmissionNumber#parse} and folds the result; contains no character test
     * of its own. Insured-limit bounds are not revalidated against BR-11 or BR-12, and a
     * contradictory range is still a {@link Narrowing} (BR-21): unlike a control character it does
     * not break the store, the predicate simply matches no row.
     *
     * <p>The same parse failure that registration turns into a refusal is, for a search, the true
     * answer "none" — one parse, two folds.
     */
    static SubmissionFilter parse(@Nullable String reference,
                                  @Nullable BigDecimal insuredLimitFrom,
                                  @Nullable BigDecimal insuredLimitTo,
                                  @Nullable Instant registeredFrom,
                                  @Nullable Instant registeredTo) {
        return SubmissionNumber.parse(reference).<SubmissionFilter>fold(
                parsed -> new Narrowing(parsed, insuredLimitFrom, insuredLimitTo,
                        registeredFrom, registeredTo),
                error -> switch (error) {
                    case SubmissionNumberError.Missing _ -> new Narrowing(null, insuredLimitFrom,
                            insuredLimitTo, registeredFrom, registeredTo);
                    case SubmissionNumberError.TooLong _ -> new MatchesNothing();
                    case SubmissionNumberError.CharacterNotAllowed _ -> new MatchesNothing();
                });
    }
}
