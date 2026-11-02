package com.example.service.submission.domain;

import com.example.service.shared.kernel.ActorRole;

/**
 * Every reason registering a submission can be refused — exhaustively, eight of them.
 *
 * <p>One sealed interface for this one operation. It is not shared with Search, and it gains no
 * variant the specification does not list.
 *
 * <p><strong>No variant carries the insured limit.</strong> BR-22 forbids the amount in any log,
 * "not partially, not rounded", explicitly including the refusals that are <em>about</em> the
 * amount. An error record ends up in a log line and in a {@code ProblemDetail}, so the amount must
 * not be inside one. {@link InsuredLimitNotPositive} therefore carries nothing at all, and
 * {@link InsuredLimitPrecisionInvalid} carries only the two scales — a scale is a property of the
 * notation, not a part of the magnitude. No variant carries the caller's identity either, while
 * OPEN-01 is open.
 */
public sealed interface RegisterSubmissionError {

    /** BR-01 — no organisation, identity and role are available. */
    record CallerNotIdentified() implements RegisterSubmissionError { }

    /** BR-02 — the caller's role is {@code VIEWER}. The role identifies nobody. */
    record RoleMayNotRegister(ActorRole role) implements RegisterSubmissionError { }

    /** BR-04 — no reference supplied, or it contains no characters at all. */
    record ReferenceMissing() implements RegisterSubmissionError { }

    /** BR-05 — reference longer than 64 characters, counted in code points. */
    record ReferenceTooLong(int maxLength, int actualLength) implements RegisterSubmissionError { }

    /** BR-10 — no insured limit supplied. */
    record InsuredLimitMissing() implements RegisterSubmissionError { }

    /** BR-11 — insured limit is zero or negative. Carries nothing: BR-22. */
    record InsuredLimitNotPositive() implements RegisterSubmissionError { }

    /** BR-12 — insured limit does not have exactly two decimal places. Scales only: BR-22. */
    record InsuredLimitPrecisionInvalid(int requiredScale, int actualScale)
            implements RegisterSubmissionError { }

    /** BR-06, BR-09 — the caller's own organisation already used this exact reference. */
    record ReferenceAlreadyUsed(String externalRef) implements RegisterSubmissionError { }
}
