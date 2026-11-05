package com.gs5.underwriting.submission.domain;

import com.gs5.underwriting.shared.kernel.ControlCharacters;
import com.gs5.underwriting.shared.kernel.LogSafeText;
import com.gs5.underwriting.shared.kernel.Result;
import org.jspecify.annotations.Nullable;

/**
 * A submission number that has been checked against BR-04, BR-05 and BR-29, and so cannot be
 * held in any other state.
 *
 * <p>A {@code final class} with a private constructor, not a record: a record's canonical
 * constructor cannot be less accessible than the record, so {@code new SubmissionNumber("\u0000")}
 * would be possible from anywhere. Raw text enters only through {@link #parse}.
 *
 * <p>Both sides use this one type: registration folds a parse failure into a refusal, and the
 * search filter folds the same failure into "matches nothing" (BR-30, BR-32). The rule is written
 * once, here, so the two cannot drift.
 */
public final class SubmissionNumber {

    /** BR-05 — measured in code points, which is also what {@code VARCHAR(64)} counts. */
    public static final int MAX_LENGTH = 64;

    private final String value;

    private SubmissionNumber(String value) {
        this.value = value;
    }

    /**
     * Presence, then length, then content — in that order, and the order is observable.
     *
     * <ul>
     *   <li><strong>{@code isEmpty()}, never {@code isBlank()}.</strong> BR-04 refuses a number
     *       with no characters at all; a number of spaces is present and valid, and BR-07
     *       forbids trimming it.</li>
     *   <li><strong>Length before content,</strong> so a number that is both too long and holds
     *       a control character is too long, and the scan is bounded to 64 code points.</li>
     * </ul>
     */
    public static Result<SubmissionNumber, SubmissionNumberError> parse(@Nullable String text) {
        if (text == null || text.isEmpty()) {
            return Result.failure(new SubmissionNumberError.Missing());
        }
        var length = text.codePointCount(0, text.length());
        if (length > MAX_LENGTH) {
            return Result.failure(new SubmissionNumberError.TooLong(MAX_LENGTH, length));
        }
        return ControlCharacters.firstIn(text)
                .<Result<SubmissionNumber, SubmissionNumberError>>map(found -> Result.failure(
                        new SubmissionNumberError.ControlCharacter(found.codePoint(),
                                found.index())))
                .orElseGet(() -> Result.success(new SubmissionNumber(text)));
    }

    /** The number exactly as supplied: never trimmed, case-folded or normalised (BR-07). */
    public String value() {
        return value;
    }

    /** Compared exactly as supplied, letter case and surrounding spaces included (BR-07). */
    @Override
    public boolean equals(@Nullable Object other) {
        return other instanceof SubmissionNumber that && value.equals(that.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    /**
     * Readable in full (BR-23). A parsed number cannot hold a control character, so the escape
     * is defence in depth rather than load-bearing — but it costs nothing and keeps every rendering
     * of a number on one path.
     */
    @Override
    public String toString() {
        return "SubmissionNumber[" + LogSafeText.escapeControlCharacters(value) + "]";
    }
}
