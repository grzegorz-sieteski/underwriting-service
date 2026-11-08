package com.gs5.underwriting.submission.adapters.persistence;

import module java.base;

import com.gs5.underwriting.submission.domain.SubmissionQueryRepository;
import com.gs5.underwriting.submission.domain.SubmissionSearchCriteria;
import com.gs5.underwriting.submission.domain.SubmissionState;
import com.gs5.underwriting.submission.domain.SubmissionSummaryView;
import org.springframework.stereotype.Component;

/**
 * Destructures the search criteria into the bound parameters of the two read statements and
 * and maps the JDBC projection to the view, which carries the submission number and no identity (BR-47).
 *
 * <p>It never loads a {@link SubmissionJdbcEntity} and never sees the aggregate — it cannot:
 * {@code Submission} is package-private in another package.
 */
@Component
public class SubmissionQuerySqlAdapter implements SubmissionQueryRepository {

    private final SubmissionQueryJdbcRepository repository;

    SubmissionQuerySqlAdapter(SubmissionQueryJdbcRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<SubmissionSummaryView> findPage(SubmissionSearchCriteria criteria,
                                                int limit,
                                                long offset) {
        return repository.findPage(criteria.tenantId(), criteria.submissionNumber(),
                        criteria.insuredLimitFrom(), criteria.insuredLimitTo(),
                        criteria.registeredFrom(), criteria.registeredTo(), limit, offset)
                .stream()
                .map(row -> new SubmissionSummaryView(
                        row.submissionNumber(), row.insuredLimit(), row.registeredBy(), row.registeredAt(),
                        row.approved() ? SubmissionState.APPROVED : SubmissionState.REGISTERED))
                .toList();
    }

    @Override
    public long countMatching(SubmissionSearchCriteria criteria) {
        return repository.countMatching(criteria.tenantId(), criteria.submissionNumber(),
                criteria.insuredLimitFrom(), criteria.insuredLimitTo(),
                criteria.registeredFrom(), criteria.registeredTo());
    }
}
