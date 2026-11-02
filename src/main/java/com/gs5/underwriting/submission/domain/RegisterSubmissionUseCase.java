package com.gs5.underwriting.submission.domain;

import module java.base;

import com.gs5.underwriting.shared.kernel.ActorContext;
import com.gs5.underwriting.shared.kernel.Result;

/**
 * The application boundary of the submission write side. There is no {@code *Service} class
 * between a controller and this useCase, because such a layer has nothing to do but delegate.
 *
 * <p>{@link #register} is a railway pipeline. Two of its properties are structural rather than
 * disciplinary: {@code Result.fromOptional(ActorContext.current(), ...)} makes BR-01 a value
 * instead of an unbound-{@code ScopedValue} throw, and {@code Result.peek} runs its side effect on
 * the success branch only, so a refusal from the insert cannot reach the publisher. The insert
 * comes first for the same reason — the BR-09 race is settled by the store, so nothing can be
 * announced until the row exists.
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
        this.repository = Objects.requireNonNull(repository, "repository");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Registers a submission for the identified caller's own organisation (BR-15), or returns one
     * of the nine refusal reasons in {@link RegisterSubmissionError}.
     */
    public Result<SubmissionId, RegisterSubmissionError> execute(RegisterSubmissionCommand command) {
        Objects.requireNonNull(command, "command");
        return Result.<ActorContext, RegisterSubmissionError>fromOptional(
                        ActorContext.current(),
                        RegisterSubmissionError.CallerNotIdentified::new)
                .flatMap(actor -> Submission.register(
                        idGenerator.nextSubmissionId(), actor.tenantId(), actor.actorId(),
                        actor.role(), command.externalRef(), command.insuredLimit(),
                        clock.instant()))
                .flatMap(submission -> repository.insert(submission.toSnapshot())
                        .<RegisterSubmissionError>mapError(error -> error)
                        .peek(_ -> eventPublisher.publish(
                                submission.registeredEvent(idGenerator.nextEventId()))));
    }
}
