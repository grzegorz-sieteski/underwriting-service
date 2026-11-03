package com.gs5.underwriting.submission.domain;

import module java.base;

import com.gs5.underwriting.shared.kernel.LogSafeText;
import com.gs5.underwriting.shared.kernel.Result;

import org.jspecify.annotations.Nullable;

/**
 * Everything a caller may supply when registering a submission — and nothing more.
 *
 * <p>The organisation, the registering caller and the registration moment are never accepted as
 * input: they come from {@code ActorContext} and from the {@code Clock} (BR-15).
 *
 * <p>Both components are {@code @Nullable} <strong>on purpose</strong>, and this record carries no
 * compact constructor that rejects null. Absence of either input is a
 * business refusal the specification names (BR-04, BR-10); turning it into a programmer-error
 * guard would convert a named refusal reason into a crash and make two of the nine variants of
 * {@link RegisterSubmissionError} unreachable.
 *
 * @param externalRef  the reference the submitting party already uses, compared byte-exactly
 * @param insuredLimit the amount of cover asked for, in euro by definition (BR-14)
 */
public record RegisterSubmissionCommand(@Nullable String externalRef,
                                        @Nullable BigDecimal insuredLimit) {

    /** BR-05 — measured in code points, not UTF-16 code units. */
    public static final int MAX_EXTERNAL_REF_LENGTH = 64;

    /** BR-12 — exactly two decimal places, read as supplied. */
    public static final int REQUIRED_INSURED_LIMIT_SCALE = 2;

    /** Masks the value a mask is for. */
    private static final String MASKED = "***";

    /**
     * Parses raw input into a command, judging in the specification's order: the reference first
     * (missing, too long, control character), then the amount (missing, not positive, wrong
     * precision). The first failure is the refusal; nothing later is examined.
     *
     * <p>The reference's own {@link ExternalReferenceError} is mapped here, exhaustively, onto the
     * three {@link RegisterSubmissionError} reference variants, so nothing a caller sees changes.
     *
     * <p><strong>RED stub.</strong> Returns {@code null}; pinned by
     * {@code RegisterSubmissionCommandParseTest}. The target record holds the two value objects
     * instead of raw components; that change belongs to the implementation.
     */
    @SuppressWarnings("NullAway")
    public static Result<RegisterSubmissionCommand, RegisterSubmissionError> parse(
            @Nullable String externalRef, @Nullable BigDecimal insuredLimit) {
        return null;
    }

    /**
     * BR-22 — the insured limit never appears in a log, not in full and not rounded. BR-23 — the
     * reference does, because audit needs it to correlate submissions.
     *
     * <p>The mask lives in {@code toString()} only. A serialiser works through record accessors,
     * so the real amount still reaches persistence and the event payload, which is required: BR-22
     * is a rule about <em>logs</em>.
     */
    @Override
    public String toString() {
        return "RegisterSubmissionCommand[externalRef=" + LogSafeText.escapeControlCharacters(externalRef)
                + ", insuredLimit=" + MASKED + "]";
    }
}
