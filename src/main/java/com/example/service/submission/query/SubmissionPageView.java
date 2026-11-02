package com.example.service.submission.query;

import module java.base;

/**
 * One page of the matching set, plus how large the whole matching set is.
 *
 * <p>{@code totalMatchCount} counts the <strong>whole</strong> matching set and ignores which page
 * was asked for (BR-26). It is reported with every page, including an empty one.
 *
 * <p>{@code page} and {@code size} echo what was <strong>actually applied</strong>, which is what
 * makes clamping safe rather than a silent data loss: a caller who asked for {@code size=500},
 * received 100 rows, and then asked for {@code page=1, size=500} would skip rows 100–499 and break
 * BR-28. Paging on the echoed values reaches every matching submission exactly once.
 *
 * <p>The rows are copied on the way in, so a later change to the caller's list cannot alter what
 * was returned.
 */
public record SubmissionPageView(List<SubmissionSummaryView> submissions,
                                 long totalMatchCount,
                                 int page,
                                 int size) {

    public SubmissionPageView {
        submissions = List.copyOf(submissions);
    }
}
