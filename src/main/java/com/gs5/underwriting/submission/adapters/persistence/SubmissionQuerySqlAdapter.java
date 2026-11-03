package com.gs5.underwriting.submission.adapters.persistence;

import module java.base;

import com.gs5.underwriting.submission.domain.SubmissionQueryRepository;
import com.gs5.underwriting.submission.domain.SubmissionSearchCriteria;
import com.gs5.underwriting.submission.domain.SubmissionId;
import com.gs5.underwriting.submission.domain.SubmissionSummaryView;
import org.springframework.stereotype.Component;

/**
 * Destructures the search criteria into the bound parameters of the two read statements and
 * and wraps the JDBC projection's UUID in the domain's typed identity.
 *
 * <p>It never loads a {@link SubmissionJdbcEntity} and never sees the aggregate — it cannot:
 * {@code Submission} is package-private in another package.
 */
@Component
class SubmissionQuerySqlAdapter implements SubmissionQueryRepository {

    private final SubmissionQueryJdbcRepository repository;

    SubmissionQuerySqlAdapter(SubmissionQueryJdbcRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<SubmissionSummaryView> findPage(SubmissionSearchCriteria criteria,
                                                int limit,
                                                long offset) {
        return repository.findPage(criteria.tenantId(), criteria.externalRef(),
                        criteria.insuredLimitFrom(), criteria.insuredLimitTo(),
                        criteria.registeredFrom(), criteria.registeredTo(), limit, offset)
                .stream()
                .map(row -> new SubmissionSummaryView(
                        new SubmissionId(row.submissionId()),
                        row.externalRef(), row.insuredLimit(), row.registeredBy(), row.registeredAt()))
                .toList();
    }

    @Override
    public long countMatching(SubmissionSearchCriteria criteria) {
        return repository.countMatching(criteria.tenantId(), criteria.externalRef(),
                criteria.insuredLimitFrom(), criteria.insuredLimitTo(),
                criteria.registeredFrom(), criteria.registeredTo());
    }
}
