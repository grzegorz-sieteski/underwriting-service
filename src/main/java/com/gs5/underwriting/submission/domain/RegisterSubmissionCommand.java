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
 * <p>Both components are validated value objects. Raw optional request fields enter through
 * {@link #parse}, where absence is converted to the named business refusal.
 *
 * @param externalRef  the reference the submitting party already uses, compared byte-exactly
 * @param insuredLimit the amount of cover asked for, in euro by definition (BR-14)
 */
public record RegisterSubmissionCommand(ExternalReference externalRef,
                                        InsuredLimit insuredLimit) {

    /** Masks the value a mask is for. */
    private static final String MASKED = "***";

    /**
     * Parses raw input into a command, judging in the specification's order: the reference first
     * (missing, too long, control character), then the amount (missing, not positive, wrong
     * precision). The first failure is the refusal; nothing later is examined.
     *
     * <p>The reference's own {@link ExternalReferenceError} is mapped here, exhaustively, onto the
     * three {@link RegisterSubmissionError} reference variants.
     *
     * <p>The resulting command contains only validated value objects. Raw data is not retained
     * after parsing.
     */
    public static Result<RegisterSubmissionCommand, RegisterSubmissionError> parse(
            @Nullable String externalRef, @Nullable BigDecimal insuredLimit) {
        return ExternalReference.parse(externalRef)
                .mapError(RegisterSubmissionCommand::asRegistrationRefusal)
                .flatMap(reference -> InsuredLimit.parse(insuredLimit)
                        .map(limit -> new RegisterSubmissionCommand(reference, limit)));
    }

    static RegisterSubmissionError asRegistrationRefusal(ExternalReferenceError error) {
        return switch (error) {
            case ExternalReferenceError.Missing _ -> new RegisterSubmissionError.ReferenceMissing();
            case ExternalReferenceError.TooLong(var maxLength, var actualLength) ->
                    new RegisterSubmissionError.ReferenceTooLong(maxLength, actualLength);
            case ExternalReferenceError.ControlCharacter(var codePoint, var index) ->
                    new RegisterSubmissionError.ReferenceContainsControlCharacter(codePoint, index);
        };
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
        return "RegisterSubmissionCommand[externalRef=" + LogSafeText.escapeControlCharacters(externalRef.value())
                + ", insuredLimit=" + MASKED + "]";
    }
}
