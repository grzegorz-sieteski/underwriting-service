package com.gs5.underwriting.submission.domain;

/**
 * Every reason approving a submission can be refused, exhaustively. None carries a value, so none
 * can carry the submission number or the internal identity (BR-43).
 */
public sealed interface ApproveSubmissionError {

    /** No submission of the caller's own organisation has this number (BR-35). */
    record SubmissionNotFound() implements ApproveSubmissionError { }

    /** The caller registered this submission, so may not approve it (BR-41). */
    record ApproverRegisteredSubmission() implements ApproveSubmissionError { }

    /** The submission has already been approved (BR-36, BR-39). */
    record SubmissionAlreadyApproved() implements ApproveSubmissionError { }
}
