package com.example.service.submission.query;

import module java.base;

/**
 * Read port: returns projection records directly. The SQL that fills them lives in the adapter,
 * which never loads an entity and never sees the aggregate.
 *
 * <p>Two methods rather than one because BR-26 needs the count of the whole matching set while the
 * page is bounded by a {@code LIMIT}. They share one criteria value so the two predicates cannot
 * drift apart.
 */
public interface SubmissionQueryRepository {

    /**
     * One page of the matching set, ordered by registration moment newest first with a
     * deterministic tie-break (BR-24, BR-28).
     *
     * <p>{@code offset} is a {@code long} because {@code page * size} with a page near
     * {@code Integer.MAX_VALUE} overflows an {@code int} and would silently go negative.
     */
    List<SubmissionSummaryView> findPage(SubmissionSearchCriteria criteria, int limit, long offset);

    /** How many submissions are in the whole matching set, ignoring the page request (BR-26). */
    long countMatching(SubmissionSearchCriteria criteria);
}
