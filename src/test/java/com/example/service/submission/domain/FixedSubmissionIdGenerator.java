package com.example.service.submission.domain;

import java.util.UUID;

/**
 * Hands out the same two ids every time, which is the whole reason
 * {@link SubmissionIdGenerator} exists: the write side stays a pure function of its inputs, so a
 * test can assert the returned {@link SubmissionId} and the event id by value.
 */
final class FixedSubmissionIdGenerator implements SubmissionIdGenerator {

    static final UUID SUBMISSION_UUID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID EVENT_UUID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Override
    public SubmissionId nextSubmissionId() {
        return new SubmissionId(SUBMISSION_UUID);
    }

    @Override
    public UUID nextEventId() {
        return EVENT_UUID;
    }
}
