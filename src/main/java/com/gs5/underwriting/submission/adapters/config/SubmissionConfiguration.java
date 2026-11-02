package com.gs5.underwriting.submission.adapters.config;

import module java.base;

import com.gs5.underwriting.submission.domain.RegisterSubmissionUseCase;
import com.gs5.underwriting.submission.domain.SubmissionCommandRepository;
import com.gs5.underwriting.submission.domain.SubmissionEventPublisher;
import com.gs5.underwriting.submission.domain.SubmissionId;
import com.gs5.underwriting.submission.domain.SubmissionIdGenerator;
import com.gs5.underwriting.submission.query.SearchSubmissionsUseCase;
import com.gs5.underwriting.submission.query.SubmissionQueryRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Where the submission module becomes a set of Spring beans.
 *
 * <p>The two useCases are <strong>plain classes constructed here</strong>, not
 * {@code @Component}-scanned: that is what keeps {@code domain/} and {@code query/} free of every
 * Spring import, which in turn is what lets every business rule be tested in milliseconds without
 * a container. The adapters are package-private {@code @Component}s in their own packages, and the
 * Spring Data repository interfaces are discovered automatically.
 */
@Configuration
class SubmissionConfiguration {

    /**
     * One {@code Clock}, declared here because no shared configuration package exists yet. The
     * second module that needs one must move this to a shared configuration rather than declare a
     * second.
     */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * The only source of randomness the write side has. It exists as a port so the aggregate never
     * calls {@code UUID.randomUUID()} itself and the write side stays a pure function of its
     * inputs — which is what lets a test pin both ids and assert them.
     */
    @Bean
    SubmissionIdGenerator submissionIdGenerator() {
        return new SubmissionIdGenerator() {

            @Override
            public SubmissionId nextSubmissionId() {
                return new SubmissionId(UUID.randomUUID());
            }

            @Override
            public UUID nextEventId() {
                return UUID.randomUUID();
            }
        };
    }

    @Bean
    RegisterSubmissionUseCase registerSubmissionUseCase(SubmissionCommandRepository repository,
                                                    SubmissionEventPublisher eventPublisher,
                                                    SubmissionIdGenerator idGenerator,
                                                    Clock clock) {
        return new RegisterSubmissionUseCase(repository, eventPublisher, idGenerator, clock);
    }

    @Bean
    SearchSubmissionsUseCase searchSubmissionsUseCase(SubmissionQueryRepository repository) {
        return new SearchSubmissionsUseCase(repository);
    }
}
