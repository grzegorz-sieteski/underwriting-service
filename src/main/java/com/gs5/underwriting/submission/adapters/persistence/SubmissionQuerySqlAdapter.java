package com.gs5.underwriting.submission.adapters.persistence;

import module java.base;

import com.gs5.underwriting.submission.query.SubmissionQueryRepository;
import com.gs5.underwriting.submission.query.SubmissionSearchCriteria;
import com.gs5.underwriting.submission.query.SubmissionSummaryView;
import org.springframework.stereotype.Component;

/**
 * Destructures the search criteria into the bound parameters of the two read statements and
 * returns the projection records untouched.
 *
 * <p>It never loads a {@link SubmissionJdbcEntity} and never sees the aggregate — it cannot:
 * {@code Submission} is package-private in another package.
 */
@Component
class SubmissionQuerySqlAdapter implements SubmissionQueryRepository {

    private final SubmissionQueryJdbcRepository repository;

    SubmissionQuerySqlAdapter(SubmissionQueryJdbcRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    @Override
    public List<SubmissionSummaryView> findPage(SubmissionSearchCriteria criteria,
                                                int limit,
                                                long offset) {
        Objects.requireNonNull(criteria, "criteria");
        return repository.findPage(criteria.tenantId(), criteria.externalRef(),
                criteria.insuredLimitFrom(), criteria.insuredLimitTo(),
                criteria.registeredFrom(), criteria.registeredTo(), limit, offset);
    }

    @Override
    public long countMatching(SubmissionSearchCriteria criteria) {
        Objects.requireNonNull(criteria, "criteria");
        return repository.countMatching(criteria.tenantId(), criteria.externalRef(),
                criteria.insuredLimitFrom(), criteria.insuredLimitTo(),
                criteria.registeredFrom(), criteria.registeredTo());
    }
}
