package com.gs5.underwriting.submission.domain;

/**
 * The one thing a test outside {@code domain/} cannot do for itself: drive the package-private
 * aggregate's {@code reconstitute} factory.
 *
 * <p>{@code Submission} is package-private and must stay that way — that is what stops a
 * controller or a JDBC adapter reaching past the facade, and the compiler enforces it for free.
 * The persistence round trip nevertheless has to prove that a snapshot loaded from a real row
 * survives {@code reconstitute(...)} and {@code toSnapshot()} unchanged, {@code version}
 * included, and that assertion lives in {@code adapters/persistence} where the adapter is.
 *
 * <p>This class lives in the test tree, in the aggregate's own package, so it can see the factory
 * and hand the result to a test in another package. It adds nothing to {@code src/main} and
 * widens nothing there: production code still cannot reach the aggregate.
 */
public final class SubmissionRoundTrip {

    private SubmissionRoundTrip() {
    }

    /**
     * Rebuilds the aggregate from {@code snapshot} and takes a fresh snapshot of it.
     *
     * <p>The result must equal the input exactly. Anything else means a field was dropped or
     * rewritten on the way through, and {@code version} is the field that actually gets dropped:
     * it is null for a never-stored aggregate and non-null for a loaded one, so a mapper or
     * factory that substitutes {@code 0L} or forgets it turns every insert into an UPDATE
     * affecting zero rows — with no exception and no row.
     */
    public static SubmissionSnapshot reconstituteAndSnapshot(SubmissionSnapshot snapshot) {
        return Submission.reconstitute(snapshot).toSnapshot();
    }
}
