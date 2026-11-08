package com.gs5.underwriting.submission.domain;

/**
 * Every reason a submission number can be invalid — exactly three, and nothing about any
 * operation.
 *
 * <p>{@link SubmissionNumber} has its own error type so it does not depend on one operation's
 * failures. Each operation maps these three into its own meaning: registration maps them onto the
 * three {@link RegisterSubmissionError} number refusals, and a search filter maps them onto
 * "not supplied" or "matches nothing", never a refusal.
 *
 * <p>No variant carries the number itself. {@link CharacterNotAllowed} carries the code point as a
 * number, so an error record cannot forge a log line.
 */
public sealed interface SubmissionNumberError {

    /** BR-04 — absent, or containing no characters at all. */
    record Missing() implements SubmissionNumberError {
    }

    /** BR-05 — longer than {@link SubmissionNumber#MAX_LENGTH} code points. */
    record TooLong(int maxLength, int actualLength) implements SubmissionNumberError {
    }

    /**
     * BR-29 (revision of 2026-11-08) — contains a character other than an ASCII letter, a digit or
     * the hyphen. Names the first such code point and its index, counted in code points.
     */
    record CharacterNotAllowed(int codePoint, int index) implements SubmissionNumberError {
    }
}
