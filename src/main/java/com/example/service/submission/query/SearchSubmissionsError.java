package com.example.service.submission.query;

/**
 * Every reason searching submissions can be refused — exhaustively, exactly one.
 *
 * <p><strong>One variant is deliberate and complete, not an oversight.</strong> Once the caller is
 * known, every search succeeds: a role never refuses a search (BR-16), matching nothing is a
 * success (BR-20), a contradictory range is a success (BR-21), and a page past the end of the
 * matching set is a success (BR-27).
 *
 * <p>Kept separate from {@code RegisterSubmissionError} so a caller searching never has to handle
 * {@code InsuredLimitPrecisionInvalid}. That the two hierarchies both carry a
 * {@code CallerNotIdentified} record is the point of per-operation errors, not duplication to
 * factor out into a shared base.
 */
public sealed interface SearchSubmissionsError {

    /** BR-01 — no organisation, identity and role are available. */
    record CallerNotIdentified() implements SearchSubmissionsError { }
}
