package com.gs5.underwriting.submission.domain;

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
 * <p><strong>RED stub.</strong> {@link #parse} and {@link #value} return {@code null}. Pinned by
 * {@code ExternalReferenceTest}.
 */
public final class ExternalReference {

    /** BR-05 — counted in code points, which is also what {@code VARCHAR(64)} counts. */
    public static final int MAX_LENGTH = 64;

    private ExternalReference() {
    }

    /**
     * Checks, in the spec's order, that the text is present (BR-04), no longer than
     * {@link #MAX_LENGTH} code points (BR-05) and free of category Cc (BR-29). The first failure is
     * the result. No trimming, case folding or normalisation is applied (BR-07).
     *
     * <p>Returns the reference's own {@link ExternalReferenceError}, not an operation's error.
     * Registration and search each map it into their own meaning.
     */
    @SuppressWarnings("NullAway")
    public static Result<ExternalReference, ExternalReferenceError> parse(@Nullable String text) {
        return null;
    }

    /** The reference exactly as supplied. */
    @SuppressWarnings("NullAway")
    public String value() {
        return null;
    }
}
