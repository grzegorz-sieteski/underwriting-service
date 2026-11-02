package com.gs5.underwriting.submission.domain;

import com.gs5.underwriting.shared.kernel.Result;

/** Outbound port: where a registered submission is recorded. */
public interface SubmissionCommandRepository {

    /**
     * Inserts a never-before-persisted submission.
     *
     * <p>Returns the refusal when the caller's own organisation already used this exact reference
     * (BR-06), including when a competing registration won the race (BR-09). Uniqueness never
     * crosses organisations (BR-08).
     *
     * <p>The error type is the single variant rather than the whole hierarchy on purpose: an
     * adapter cannot fabricate a role or precision refusal.
     */
    Result<SubmissionId, RegisterSubmissionError.ReferenceAlreadyUsed> insert(
            SubmissionSnapshot snapshot);
}
