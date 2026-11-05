package com.gs5.underwriting.submission.domain;

/**
 * Every reason a submission reference can be invalid — exactly three, and nothing about any
 * operation.
 *
 * <p>{@link ExternalReference} has its own error type so it does not depend on one operation's
 * failures. Each operation maps these three into its own meaning: registration maps them onto the
 * three {@link RegisterSubmissionError} reference refusals, and a search filter maps them onto
 * "not supplied" or "matches nothing", never a refusal.
 *
 * <p>No variant carries the reference itself. {@link ControlCharacter} carries the code point as a
 * number, so an error record cannot forge a log line.
 */
public sealed interface ExternalReferenceError {

    /** BR-04 — absent, or containing no characters at all. */
    record Missing() implements ExternalReferenceError {
    }

    /** BR-05 — longer than {@link ExternalReference#MAX_LENGTH} code points. */
    record TooLong(int maxLength, int actualLength) implements ExternalReferenceError {
    }

    /** BR-29 — contains a character of category Cc; its index is counted in code points. */
    record ControlCharacter(int codePoint, int index) implements ExternalReferenceError {
    }
}
