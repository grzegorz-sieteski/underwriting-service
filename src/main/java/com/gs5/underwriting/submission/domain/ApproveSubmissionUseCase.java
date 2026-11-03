package com.gs5.underwriting.submission.domain;

import module java.base;

import com.gs5.underwriting.shared.kernel.ActorContext;
import com.gs5.underwriting.shared.kernel.Result;

import java.time.Clock;
import java.util.UUID;

/** Approves an existing submission for the caller's own organisation. */
public final class ApproveSubmissionUseCase {

    private final SubmissionCommandRepository commandRepository;
    private final SubmissionApprovalRepository approvalRepository;
    private final SubmissionEventPublisher eventPublisher;
    private final Clock clock;

    public ApproveSubmissionUseCase(SubmissionCommandRepository commandRepository,
                                    SubmissionApprovalRepository approvalRepository,
                                    SubmissionEventPublisher eventPublisher,
                                    Clock clock) {
        this.commandRepository = commandRepository;
        this.approvalRepository = approvalRepository;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    public Result<SubmissionId, ApproveSubmissionError> execute(
            ActorContext actor, ApproveSubmissionCommand command) {
        var submission = commandRepository.findOwn(actor.tenantId(), command.submissionId().value());
        if (submission.isEmpty()) {
            return Result.failure(
                    new ApproveSubmissionError.SubmissionNotFound(command.submissionId()));
        }
        if (approvalRepository.isApproved(actor.tenantId(), command.submissionId())) {
            return Result.failure(
                    new ApproveSubmissionError.SubmissionAlreadyApproved(command.submissionId()));
        }
        var approvedAt = clock.instant();
        return approvalRepository.approve(actor.tenantId(), command.submissionId(), actor, approvedAt)
                .peek(id -> eventPublisher.publish(
                        new SubmissionDomainEvent.SubmissionApproved(
                                UUID.randomUUID(),
                                id,
                                actor.tenantId(),
                                actor.actorId(),
                                approvedAt)));
    }
}
