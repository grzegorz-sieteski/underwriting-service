package com.gs5.underwriting.submission.domain;

import module java.base;

/**
 * Typed identity of a submission.
 *
 * <p>Generated in the domain through {@link SubmissionIdGenerator}, never by the database, which
 * is what makes the write side a pure function of its inputs.
 *
 * @param value the raw identifier carried to persistence and into the domain event
 */
public record SubmissionId(UUID value) { }
