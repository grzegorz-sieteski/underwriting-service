package com.gs5.underwriting.submission.domain;

import com.gs5.underwriting.shared.kernel.ActorContext;
import com.gs5.underwriting.shared.kernel.Result;

import java.time.Clock;

/** Approves an existing submission for the caller's own organisation, named by its number. */
public final class ApproveSubmissionUseCase {

    private final SubmissionCommandRepository commandRepository;
    private final SubmissionApprovalRepository approvalRepository;
    private final SubmissionEventPublisher eventPublisher;
    private final SubmissionIdGenerator idGenerator;
    private final Clock clock;

    public ApproveSubmissionUseCase(SubmissionCommandRepository commandRepository,
                                    SubmissionApprovalRepository approvalRepository,
                                    SubmissionEventPublisher eventPublisher,
                                    SubmissionIdGenerator idGenerator,
                                    Clock clock) {
        this.commandRepository = commandRepository;
        this.approvalRepository = approvalRepository;
        this.eventPublisher = eventPublisher;
        this.idGenerator = idGenerator;
        this.clock = clock;
    }

    /** Success returns the submission number, the only identifier that leaves the service. */
    public Result<SubmissionNumber, ApproveSubmissionError> execute(
            ActorContext actor, ApproveSubmissionCommand command) {
        var found = commandRepository.findOwnByNumber(actor.tenantId(), command.submissionNumber())
                .flatMap(Submission::reconstitute);
        if (found.isEmpty()) {
            return Result.failure(new ApproveSubmissionError.SubmissionNotFound());
        }
        var submission = found.get();
        if (submission.isRegisteredBy(actor)) {
            return Result.failure(new ApproveSubmissionError.ApproverRegisteredSubmission());
        }
        var internalId = submission.id();
        var approvedAt = clock.instant();
        return approvalRepository.approve(actor.tenantId(), internalId, actor, approvedAt)
                .map(id -> command.submissionNumber())
                .peek(number -> eventPublisher.publish(
                        new SubmissionDomainEvent.SubmissionApproved(
                                idGenerator.nextEventId(),
                                number,
                                actor.tenantId(),
                                actor.actorId(),
                                approvedAt)));
    }
}
