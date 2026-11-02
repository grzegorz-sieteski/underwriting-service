package com.example.service.submission.domain;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Records what was announced, so "the event is never raised for a refused registration" is an
 * assertion rather than a comment.
 *
 * <p>{@code CopyOnWriteArrayList} rather than a plain list: the port's own contract says nothing
 * about which thread publishes, and a fake that is only correct on one thread would quietly cap
 * what the suite can assert.
 */
final class InMemorySubmissionEventPublisher implements SubmissionEventPublisher {

    private final List<SubmissionDomainEvent> published = new CopyOnWriteArrayList<>();

    @Override
    public void publish(SubmissionDomainEvent event) {
        published.add(event);
    }

    List<SubmissionDomainEvent> publishedEvents() {
        return List.copyOf(published);
    }
}
