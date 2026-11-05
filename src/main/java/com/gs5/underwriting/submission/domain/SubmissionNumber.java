package com.gs5.underwriting.submission.domain;

import com.gs5.underwriting.shared.kernel.ControlCharacters;
import com.gs5.underwriting.shared.kernel.LogSafeText;
import com.gs5.underwriting.shared.kernel.Result;
import org.jspecify.annotations.Nullable;

/**
 * A submission reference that has been checked against BR-04, BR-05 and BR-29, and so cannot be
 * held in any other state.
 *
 * <p>A {@code final class} with a private constructor, not a record: a record's canonical
 * constructor cannot be less accessible than the record, so {@code new ExternalReference("\u0000")}
 * would be possible from anywhere. Raw text enters only through {@link #parse}.
 *
 * <p>Both sides use this one type: registration folds a parse failure into a refusal, and the
 * search filter folds the same failure into "matches nothing" (BR-30, BR-32). The rule is written
 * once, here, so the two cannot drift.
 */
public final class ExternalReference {

    /** BR-05 — measured in code points, which is also what {@code VARCHAR(64)} counts. */
    public static final int MAX_LENGTH = 64;

    private final String value;

    private ExternalReference(String value) {
        this.value = value;
    }

    /**
     * Presence, then length, then content — in that order, and the order is observable.
     *
     * <ul>
     *   <li><strong>{@code isEmpty()}, never {@code isBlank()}.</strong> BR-04 refuses a reference
     *       with no characters at all; a reference of spaces is present and valid, and BR-07
     *       forbids trimming it.</li>
     *   <li><strong>Length before content,</strong> so a reference that is both too long and holds
     *       a control character is too long, and the scan is bounded to 64 code points.</li>
     * </ul>
     */
    public static Result<ExternalReference, ExternalReferenceError> parse(@Nullable String text) {
        if (text == null || text.isEmpty()) {
            return Result.failure(new ExternalReferenceError.Missing());
        }
        var length = text.codePointCount(0, text.length());
        if (length > MAX_LENGTH) {
            return Result.failure(new ExternalReferenceError.TooLong(MAX_LENGTH, length));
        }
        return ControlCharacters.firstIn(text)
                .<Result<ExternalReference, ExternalReferenceError>>map(found -> Result.failure(
                        new ExternalReferenceError.ControlCharacter(found.codePoint(),
                                found.index())))
                .orElseGet(() -> Result.success(new ExternalReference(text)));
    }

    /** The reference exactly as supplied: never trimmed, case-folded or normalised (BR-07). */
    public String value() {
        return value;
    }

    /** Compared exactly as supplied, letter case and surrounding spaces included (BR-07). */
    @Override
    public boolean equals(@Nullable Object other) {
        return other instanceof ExternalReference that && value.equals(that.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    /**
     * Readable in full (BR-23). A parsed reference cannot hold a control character, so the escape
     * is defence in depth rather than load-bearing — but it costs nothing and keeps every rendering
     * of a reference on one path.
     */
    @Override
    public String toString() {
        return "ExternalReference[" + LogSafeText.escapeControlCharacters(value) + "]";
    }
}
