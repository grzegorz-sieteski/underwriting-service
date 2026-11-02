package com.example.service.submission.domain;

/** Outbound port: how a business fact is announced. */
public interface SubmissionEventPublisher {

    /**
     * Announces a business fact. How, and to whom, is an adapter decision this module knows
     * nothing about — no transport, no topic, no message key, no wire contract.
     *
     * <p>Returns {@code void} and not a {@code Result} because the specification lists no refusal
     * reason for announcing a fact; a {@code Result} here would force a tenth error variant for
     * something the business never described as an outcome.
     */
    void publish(SubmissionDomainEvent event);
}
