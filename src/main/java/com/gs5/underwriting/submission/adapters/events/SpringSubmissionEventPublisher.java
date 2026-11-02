package com.gs5.underwriting.submission.adapters.events;

import module java.base;

import com.gs5.underwriting.submission.domain.SubmissionDomainEvent;
import com.gs5.underwriting.submission.domain.SubmissionEventPublisher;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * The publishing adapter, and the resolution of the one adapter decision the design deferred.
 *
 * <p><strong>Chosen: Spring's {@link ApplicationEventPublisher}.</strong> The design listed four
 * candidates and chose none, directing this stage to pick "the simplest adapter that satisfies the
 * port and is genuinely testable". This is it: no infrastructure to configure, in-process
 * listeners, and nothing to stand up in a test.
 *
 * <p><strong>What it does not buy, stated plainly.</strong> It is not atomic with the insert, and
 * nothing crosses a process boundary. That is exactly the at-most-once announcement the design
 * already accepts and documents: the state change is exactly-once, the reference is a
 * caller-supplied natural key unique per organisation, so a retry after an ambiguous failure
 * returns {@code ReferenceAlreadyUsed} rather than registering twice. No candidate that would
 * change this was available without a transaction boundary decision the design deliberately left
 * open.
 *
 * <p><strong>Why no wire contract type.</strong> A domain record is not a wire format — but this
 * adapter does not cross a process boundary, so it needs no versioned contract at all. The moment
 * a transport is introduced behind this port, that transport's adapter owes one; serialising
 * {@link SubmissionDomainEvent} directly would weld every recipient to this module's internals.
 *
 * <p><strong>Nothing is logged here.</strong> BR-22 forbids the insured limit in any log, and the
 * strict default applied to OPEN-01 forbids the registering caller. The safe way to honour both in
 * an adapter that handles a record carrying both is to write no log statement — and the records'
 * own masked {@code toString()} means even a future one could not disclose them.
 */
@Component
class SpringSubmissionEventPublisher implements SubmissionEventPublisher {

    private final ApplicationEventPublisher delegate;

    SpringSubmissionEventPublisher(ApplicationEventPublisher delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public void publish(SubmissionDomainEvent event) {
        Objects.requireNonNull(event, "event");
        delegate.publishEvent(event);
    }
}
