package com.gs5.underwriting.submission.domain;

import com.gs5.underwriting.shared.kernel.Result;

import java.math.BigDecimal;

import org.jspecify.annotations.Nullable;

/**
 * An insured limit that is present, strictly positive and written with exactly two decimal places.
 *
 * <p>A {@code final class} for the same reason as {@link SubmissionNumber}: an invariant that a
 * record's public canonical constructor would let anyone bypass. Raw amounts enter only through
 * {@link #parse}.
 *
 * <p>Euro by definition (BR-14) and unbounded above (BR-13): there is no currency field and no
 * maximum, and none may be introduced without a business decision.
 */
public final class InsuredLimit {

    /** BR-12 — exactly two decimal places, read as supplied. */
    public static final int REQUIRED_SCALE = 2;

    private final BigDecimal amount;

    private InsuredLimit(BigDecimal amount) {
        this.amount = amount;
    }

    /**
     * Presence, then sign, then scale. {@code BigDecimal.ZERO} has scale 0 and so violates both
     * BR-11 and BR-12; checking the sign first reports the more fundamental fact.
     *
     * <p>The scale is read <strong>as supplied</strong>: no {@code stripTrailingZeros()} and no
     * {@code setScale()}, either of which would make {@code 1200} and {@code 1200.00}
     * indistinguishable. A scale is a field read, so an exponent such as {@code 1E+2000000000} is
     * refused without ever being expanded into digits.
     */
    public static Result<InsuredLimit, RegisterSubmissionError> parse(@Nullable BigDecimal amount) {
        if (amount == null) {
            return Result.failure(new RegisterSubmissionError.InsuredLimitMissing());
        }
        if (amount.signum() <= 0) {
            return Result.failure(new RegisterSubmissionError.InsuredLimitNotPositive());
        }
        var scale = amount.scale();
        return scale == REQUIRED_SCALE
                ? Result.success(new InsuredLimit(amount))
                : Result.failure(
                        new RegisterSubmissionError.InsuredLimitPrecisionInvalid(REQUIRED_SCALE, scale));
    }

    /** The amount exactly as supplied, scale included. */
    public BigDecimal amount() {
        return amount;
    }

    /** Value equality. Both scales are 2 by construction, so {@code equals} is numeric equality. */
    @Override
    public boolean equals(@Nullable Object other) {
        return other instanceof InsuredLimit that && amount.equals(that.amount);
    }

    @Override
    public int hashCode() {
        return amount.hashCode();
    }

    /** BR-22 — the amount never appears in a log, not in full and not rounded. */
    @Override
    public String toString() {
        return "InsuredLimit[***]";
    }
}
