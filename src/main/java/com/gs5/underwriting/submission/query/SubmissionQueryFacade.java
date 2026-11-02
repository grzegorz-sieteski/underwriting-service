package com.gs5.underwriting.submission.query;

import module java.base;

import com.gs5.underwriting.shared.kernel.ActorContext;
import com.gs5.underwriting.shared.kernel.Result;

/**
 * The application boundary of the submission read side.
 *
 * <p>This facade must <strong>never</strong> read the actor's role — BR-16: a role neither narrows
 * nor widens a search. It must read the actor's tenant, which is the only thing BR-17 allows to
 * decide what comes back.
 *
 * <p>BR-20, BR-21 and BR-27 need no code branch at all: a contradictory predicate matches no row,
 * and an offset past the end returns none while the count stays true — so a short-circuit for any
 * of the three would be a second implementation of the same rule.
 */
public final class SubmissionQueryFacade {

    private final SubmissionQueryRepository repository;

    public SubmissionQueryFacade(SubmissionQueryRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    /**
     * Returns one page of the caller's own organisation's submissions, plus the total match count
     * of the whole matching set — or the single refusal reason Search has.
     */
    public Result<SubmissionPageView, SearchSubmissionsError> search(SearchSubmissionsQuery query) {
        Objects.requireNonNull(query, "query");
        return Result.<ActorContext, SearchSubmissionsError>fromOptional(
                        ActorContext.current(),
                        SearchSubmissionsError.CallerNotIdentified::new)
                .map(actor -> pageFor(actor.tenantId(), query));
    }

    /**
     * {@code offset} is computed as a {@code long}: {@code page * size} with a page near
     * {@code Integer.MAX_VALUE} overflows an {@code int} and would silently go negative.
     */
    private SubmissionPageView pageFor(UUID tenantId, SearchSubmissionsQuery query) {
        var criteria = new SubmissionSearchCriteria(tenantId, query.externalRef(),
                query.insuredLimitFrom(), query.insuredLimitTo(),
                query.registeredFrom(), query.registeredTo());
        return new SubmissionPageView(
                repository.findPage(criteria, query.size(), (long) query.page() * query.size()),
                repository.countMatching(criteria),
                query.page(), query.size());
    }
}
