package com.gs5.underwriting.submission.domain;

/**
 * The lifecycle state a search result shows: exactly one value, the most current one (BR-45, BR-46).
 * An earlier state is never shown as current.
 */
public enum SubmissionState {
    /** Registered, and not yet approved. */
    REGISTERED,
    /** Approved, after the registration fact, which stays on record (BR-44). */
    APPROVED
}
