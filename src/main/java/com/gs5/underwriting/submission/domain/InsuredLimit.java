package com.gs5.underwriting.submission.domain;

import com.gs5.underwriting.shared.kernel.Result;

import java.math.BigDecimal;

import org.jspecify.annotations.Nullable;

/**
 * An insured limit that is present, strictly positive and written with exactly two decimal places.
 *
 * <p>A {@code final class} for the same reason as {@link ExternalReference}: an invariant that a
 * record's public canonical constructor would let anyone bypass. Raw amounts enter only through
 * {@link #parse}.
 *
 * <p><strong>RED stub.</strong> {@link #parse} and {@link #amount} return {@code null}. Pinned by
 * {@code InsuredLimitTest}.
 */
public final class InsuredLimit {

    /** BR-12 — exactly two decimal places, read as supplied. */
    public static final int REQUIRED_SCALE = 2;

    private InsuredLimit() {
    }

    /**
     * Checks, in the spec's order, that the amount is present (BR-10), strictly positive (BR-11)
     * and has scale exactly {@link #REQUIRED_SCALE} (BR-12). The sign is checked before the scale,
     * so {@code BigDecimal.ZERO} is refused as not positive. The scale is read as supplied, never
     * stripped or set. There is no upper bound (BR-13).
     */
    @SuppressWarnings("NullAway")
    public static Result<InsuredLimit, RegisterSubmissionError> parse(@Nullable BigDecimal amount) {
        return null;
    }

    /** The amount exactly as supplied and validated. */
    @SuppressWarnings("NullAway")
    public BigDecimal amount() {
        return null;
    }
}
