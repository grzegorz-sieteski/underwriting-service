package com.gs5.underwriting.submission.domain;

/**
 * Every domain reason registering a submission can be refused — exhaustively, seven of them.
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

    /** BR-04 — no reference supplied, or it contains no characters at all. */
    record ReferenceMissing() implements RegisterSubmissionError { }

    /** BR-05 — reference longer than 64 characters, counted in code points. */
    record ReferenceTooLong(int maxLength, int actualLength) implements RegisterSubmissionError { }

    /**
     * BR-29 — the reference contains a character of Unicode general category {@code Cc}, that is
     * one in U+0000–U+001F or U+007F–U+009F. NUL, TAB and NEWLINE are all such characters;
     * <strong>SPACE (U+0020) is not</strong>.
     *
     * <p>It carries two <em>numbers</em> and deliberately not the reference, although BR-23 makes a
     * reference loggable in full. The offending character is by definition unprintable, and the
     * whole point of this refusal is that such a character must not travel any further: a raw
     * NEWLINE in an error record forges a log line, and a raw NUL truncates one. Rendering the
     * code point as an integer names the problem exactly while keeping the character itself out of
     * every log and every {@code ProblemDetail}.
     *
     * @param codePoint the first offending character's code point, as a number — never the
     *                  character
     * @param index     its zero-based <strong>code point</strong> index, counted the same way
     *                  BR-05 counts length, so the two refusals measure positions alike
     */
    record ReferenceContainsControlCharacter(int codePoint, int index)
            implements RegisterSubmissionError { }

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
