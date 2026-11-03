package com.gs5.underwriting.submission.domain;

import module java.base;

import com.gs5.underwriting.shared.kernel.ActorContext;
import com.gs5.underwriting.shared.kernel.Result;

/**
 * The application boundary of the submission write side. There is no {@code *Service} class
 * between a controller and this useCase, because such a layer has nothing to do but delegate.
 *
 * <p>Caller identification and role permission are decided by the inbound access gate, before
 * request data is parsed. This use case receives that admitted actor and a parsed command; its
 * business refusals are the registration rules and the tenant-scoped uniqueness result.
 */
public final class RegisterSubmissionUseCase {

    private final SubmissionCommandRepository repository;
    private final SubmissionEventPublisher eventPublisher;
    private final SubmissionIdGenerator idGenerator;
    private final Clock clock;

    public RegisterSubmissionUseCase(SubmissionCommandRepository repository,
                                   SubmissionEventPublisher eventPublisher,
                                   SubmissionIdGenerator idGenerator,
                                   Clock clock) {
        this.repository = repository;
        this.eventPublisher = eventPublisher;
        this.idGenerator = idGenerator;
        this.clock = clock;
    }

    /**
     * Registers a submission for the admitted caller's own organisation (BR-15).
     */
    public Result<SubmissionId, RegisterSubmissionError> execute(
            ActorContext actor, RegisterSubmissionCommand command) {
        var submission = Submission.register(idGenerator.nextSubmissionId(), actor,
                command.externalRef(), command.insuredLimit(), clock.instant());
        return repository.insert(submission.toSnapshot())
                .<RegisterSubmissionError>mapError(error -> error)
                .peek(_ -> eventPublisher.publish(
                        submission.registeredEvent(idGenerator.nextEventId())));
    }
}
