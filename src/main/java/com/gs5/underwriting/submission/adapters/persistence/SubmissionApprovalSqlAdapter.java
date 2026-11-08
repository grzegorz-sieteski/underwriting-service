package com.gs5.underwriting.submission.adapters.persistence;

import module java.base;

import com.gs5.underwriting.shared.kernel.ActorContext;
import com.gs5.underwriting.shared.kernel.Result;
import com.gs5.underwriting.submission.domain.ApproveSubmissionError;
import com.gs5.underwriting.submission.domain.SubmissionApprovalRepository;
import com.gs5.underwriting.submission.domain.SubmissionId;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
public class SubmissionApprovalSqlAdapter implements SubmissionApprovalRepository {

    private final SubmissionApprovalJdbcRepository repository;

    SubmissionApprovalSqlAdapter(SubmissionApprovalJdbcRepository repository) {
        this.repository = repository;
    }

    @Override
    public Result<SubmissionId, ApproveSubmissionError> approve(
            UUID tenantId,
            SubmissionId submissionId,
            ActorContext actor,
            Instant approvedAt) {
        var insertedRows = repository.insertIfNotApproved(submissionId.value(), tenantId,
                actor.actorId(), approvedAt);
        return insertedRows == 1
                ? Result.success(submissionId)
                : Result.failure(new ApproveSubmissionError.SubmissionAlreadyApproved());
    }
}
