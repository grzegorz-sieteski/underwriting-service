package com.gs5.underwriting.submission.domain;

import module java.base;

import org.jspecify.annotations.Nullable;

/**
 * One search: five optional filters (BR-19), combined with AND (BR-18), plus a page request.
 *
 * <p>The filters are {@code @Nullable} rather than {@code Optional} because absence has to survive
 * all the way into a bound SQL parameter ({@code :param IS NULL OR ...}), and an {@code Optional}
 * cannot be bound to a {@code @Param}.
 *
 * <p>Filter bounds are <strong>not</strong> re-validated against BR-11 or BR-12: those are rules
 * about what may be <em>registered</em>. A bound may be negative, zero, or carry any scale.
 *
 * <p><strong>The page request normalises itself here, in the compact constructor.</strong> Page
 * numbering is 0-based, {@code size} is clamped into {@code [MIN_PAGE_SIZE, MAX_PAGE_SIZE]} and
 * {@code page} to {@code >= 0}. Inside the record, so the useCase, the adapter and the response
 * body cannot disagree about what was applied, and the {@code size} echoed to the caller is by
 * construction the size actually used. An out-of-range page request clamps and never refuses,
 * because Search has exactly one refusal reason.
 */
public record SearchSubmissionsQuery(SubmissionFilter filter,
                                     int page,
                                     int size) {

    public static final int MIN_PAGE_SIZE = 1;

    /** Engineering decision, recorded in the design: resource protection, not a business rule. */
    public static final int MAX_PAGE_SIZE = 100;

    public SearchSubmissionsQuery {
        page = Math.max(0, page);
        size = Math.clamp(size, MIN_PAGE_SIZE, MAX_PAGE_SIZE);
    }

    /**
     * Parses raw transport filters into the value carried by this query. Search filters are total:
     * an invalid number becomes {@link SubmissionFilter.MatchesNothing}, not a refusal.
     */
    public static SearchSubmissionsQuery parse(@Nullable String submissionNumber,
                                               @Nullable BigDecimal insuredLimitFrom,
                                               @Nullable BigDecimal insuredLimitTo,
                                               @Nullable Instant registeredFrom,
                                               @Nullable Instant registeredTo,
                                               int page,
                                               int size) {
        return new SearchSubmissionsQuery(
                SubmissionFilter.parse(submissionNumber, insuredLimitFrom, insuredLimitTo,
                        registeredFrom, registeredTo),
                page,
                size);
    }

    /**
     * The insured limit bounds are masked, which is the strict reading of BR-22: a logged search
     * for {@code insuredLimitFrom=1200.00, insuredLimitTo=1200.00} that returns one row discloses
     * that row's amount. The number is printed in full (BR-23).
     */
    @Override
    public String toString() {
        return "SearchSubmissionsQuery[filter=" + filter
                + ", page=" + page
                + ", size=" + size + "]";
    }
}
