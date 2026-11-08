package com.gs5.underwriting.submission.domain;

import com.gs5.underwriting.shared.kernel.LogSafeText;
import com.gs5.underwriting.shared.kernel.Result;
import org.jspecify.annotations.Nullable;

import java.util.stream.IntStream;

/**
 * A submission number that has been checked against BR-04, BR-05 and BR-29, and so cannot be
 * held in any other state.
 *
 * <p>BR-29, revision of 2026-11-08: a number has no special characters. It consists only of
 * {@code A}-{@code Z}, {@code a}-{@code z}, {@code 0}-{@code 9} and {@code -}. Every one of them is
 * ASCII, so a code point is a character, and nothing in a number has a meaning in a URL path:
 * this is what keeps {@code REF;1} from naming {@code REF} (design section 21.2).
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
     *       with no characters at all. A number of spaces is present, so it is not missing: it is
     *       refused by BR-29 instead, and BR-07 forbids trimming it into something else.</li>
     *   <li><strong>Length before content,</strong> so a number that is both too long and holds
     *       a character that is not allowed is too long, and the scan is bounded to 64 code
     *       points.</li>
     *   <li><strong>An allow list, not a deny list.</strong> The check names what is accepted, so
     *       a character nobody thought of is refused rather than let through.</li>
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
        var codePoints = text.codePoints().toArray();
        return IntStream.range(0, codePoints.length)
                .filter(index -> !isAllowed(codePoints[index]))
                .boxed()
                .findFirst()
                .<Result<SubmissionNumber, SubmissionNumberError>>map(index -> Result.failure(
                        new SubmissionNumberError.CharacterNotAllowed(codePoints[index], index)))
                .orElseGet(() -> Result.success(new SubmissionNumber(text)));
    }

    /**
     * BR-29: ASCII letters, digits and the hyphen. Written out rather than with
     * {@link Character#isLetterOrDigit}, which accepts every script, and checked against the JDK:
     * no {@code Character} or {@code java.util.regex} predicate names exactly this set.
     */
    private static boolean isAllowed(int codePoint) {
        return (codePoint >= 'A' && codePoint <= 'Z')
                || (codePoint >= 'a' && codePoint <= 'z')
                || (codePoint >= '0' && codePoint <= '9')
                || codePoint == '-';
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
     * Readable in full (BR-23). A parsed number holds only ASCII letters, digits and hyphens, so
     * the escape is defence in depth rather than load-bearing, but it costs nothing and keeps
     * every rendering of a number on one path.
     */
    @Override
    public String toString() {
        return "SubmissionNumber[" + LogSafeText.escapeControlCharacters(value) + "]";
    }
}
