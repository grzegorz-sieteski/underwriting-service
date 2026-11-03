package com.gs5.underwriting.submission.domain;

import com.gs5.underwriting.shared.kernel.ActorContext;
import com.gs5.underwriting.shared.kernel.Result;

import java.time.Instant;
import java.util.UUID;

/** Outbound port: where a submission approval is recorded. */
public interface SubmissionApprovalRepository {

    boolean isApproved(UUID tenantId, SubmissionId submissionId);

    Result<SubmissionId, ApproveSubmissionError> approve(
            UUID tenantId,
            SubmissionId submissionId,
            ActorContext actor,
            Instant approvedAt);
}
