package com.example.service.submission.domain;

import module java.base;

/**
 * Outbound port: the only source of randomness the write side has.
 *
 * <p>It exists so the aggregate never calls {@code UUID.randomUUID()} and the write side stays a
 * pure function of its inputs — which is what lets a test pin both ids and assert them.
 */
public interface SubmissionIdGenerator {

    SubmissionId nextSubmissionId();

    UUID nextEventId();
}
