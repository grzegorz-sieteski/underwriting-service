package com.gs5.underwriting.submission.query;

import module java.base;

import com.gs5.underwriting.shared.kernel.LogSafeText;
import org.jspecify.annotations.Nullable;

/**
 * What the read port is asked to match: the tenant predicate plus the five optional filters.
 *
 * <p>{@code tenantId} lives inside the criteria and only the useCase can build one. It is not a
 * filter the caller supplies and not something an adapter may forget: BR-17 is a business rule, so
 * the tenant predicate is part of the port's vocabulary.
 */
public record SubmissionSearchCriteria(UUID tenantId,
                                       @Nullable String externalRef,
                                       @Nullable BigDecimal insuredLimitFrom,
                                       @Nullable BigDecimal insuredLimitTo,
                                       @Nullable Instant registeredFrom,
                                       @Nullable Instant registeredTo) {

    private static final String MASKED = "***";

    /**
     * Masks both insured limit bounds, for the same reason {@link SearchSubmissionsQuery} does
     * (finding QA-01, which was that this record did <em>not</em>).
     *
     * <p>This record carries the identical two values one step further down than the query does —
     * into the adapter, where a bound parameter, a {@code requireNonNull} message or any future
     * diagnostic prints them — so masking at the query alone left the disclosure intact: a logged
     * search for {@code from=1200.00, to=1200.00} that returns one row discloses that row's
     * amount, wherever it is logged.
     *
     * <p>The reference is printed in full (BR-23) with category-Cc code points escaped, because a
     * filter value is caller-supplied and an unescaped NEWLINE in a log line is log injection.
     */
    @Override
    public String toString() {
        return "SubmissionSearchCriteria[tenantId=" + tenantId
                + ", externalRef=" + LogSafeText.escapeControlCharacters(externalRef)
                + ", insuredLimitFrom=" + MASKED
                + ", insuredLimitTo=" + MASKED
                + ", registeredFrom=" + registeredFrom
                + ", registeredTo=" + registeredTo + "]";
    }
}
