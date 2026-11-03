package com.gs5.underwriting.submission.adapters.access;

/**
 * Evidence that a permission was checked for this operation. Only {@link OperationGate} mints one.
 *
 * <p>{@link #run} supplies the admitted actor, so the operation cannot be run for a different actor
 * from the one that was checked.
 *
 * <p><strong>RED stub.</strong> {@link #run} returns {@code null}; pinned by
 * {@code OperationGateTest}.
 *
 * @param <C> the raw input
 * @param <R> the operation's result
 */
public final class Admission<C, R> {

    private Admission() {
    }

    /** Runs the guarded operation for exactly the admitted actor. */
    @SuppressWarnings("NullAway")
    public R run(C input) {
        return null;
    }
}
