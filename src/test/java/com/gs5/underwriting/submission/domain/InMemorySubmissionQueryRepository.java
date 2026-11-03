package com.gs5.underwriting.submission.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/**
 * An in-memory stand-in for the two read statements, with exactly the semantics the SQL in the
 * design has: the tenant predicate with no {@code OR}, five optional filters combined with
 * {@code AND}, inclusive range bounds, and {@code ORDER BY registered_at DESC, id DESC}.
 *
 * <p>The tenant column is held here rather than on {@link SubmissionSummaryView}, because the view
 * is what a caller receives and it has no business carrying another organisation's existence.
 *
 * <p>{@code countMatching} deliberately shares one predicate with {@code findPage} and ignores
 * {@code limit} and {@code offset}: BR-26 counts the whole matching set, and two predicates that
 * could drift apart would hide exactly the bug this fake exists to catch.
 */
final class InMemorySubmissionQueryRepository implements SubmissionQueryRepository {

    private record StoredSubmission(UUID tenantId, SubmissionSummaryView view) { }

    private final List<StoredSubmission> rows = new CopyOnWriteArrayList<>();

    @Override
    public List<SubmissionSummaryView> findPage(SubmissionSearchCriteria criteria,
                                                int limit,
                                                long offset) {
        return matching(criteria)
                .skip(offset < 0 ? 0 : offset)
                .limit(limit < 0 ? 0 : limit)
                .toList();
    }

    @Override
    public long countMatching(SubmissionSearchCriteria criteria) {
        return matching(criteria).count();
    }

    private java.util.stream.Stream<SubmissionSummaryView> matching(
            SubmissionSearchCriteria criteria) {
        return rows.stream()
                .filter(row -> row.tenantId().equals(criteria.tenantId()))
                .map(StoredSubmission::view)
                .filter(matches(criteria))
                .sorted(Comparator.comparing(SubmissionSummaryView::registeredAt)
                        .thenComparing(view -> view.submissionId().toString())
                        .reversed());
    }

    private static Predicate<SubmissionSummaryView> matches(SubmissionSearchCriteria criteria) {
        return view ->
                (criteria.externalRef() == null || view.externalRef().equals(criteria.externalRef()))
                        && (criteria.insuredLimitFrom() == null
                            || view.insuredLimit().compareTo(criteria.insuredLimitFrom()) >= 0)
                        && (criteria.insuredLimitTo() == null
                            || view.insuredLimit().compareTo(criteria.insuredLimitTo()) <= 0)
                        && (criteria.registeredFrom() == null
                            || !view.registeredAt().isBefore(criteria.registeredFrom()))
                        && (criteria.registeredTo() == null
                            || !view.registeredAt().isAfter(criteria.registeredTo()));
    }

    // ------------------------------------------------------------------ arrange

    SubmissionSummaryView givenSubmission(UUID tenantId,
                                          UUID submissionId,
                                          String externalRef,
                                          String insuredLimit,
                                          Instant registeredAt) {
        var view = new SubmissionSummaryView(submissionId, externalRef,
                new BigDecimal(insuredLimit), "uw-01", registeredAt);
        rows.add(new StoredSubmission(tenantId, view));
        return view;
    }
}
