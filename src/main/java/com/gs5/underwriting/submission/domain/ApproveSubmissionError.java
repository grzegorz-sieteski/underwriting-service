package com.gs5.underwriting.submission.domain;

/** Every reason approving a submission can be refused — exhaustively. */
public sealed interface ApproveSubmissionError {

    /** The submission is not in the caller's own organisation. */
    record SubmissionNotFound(SubmissionId submissionId) implements ApproveSubmissionError { }

    /** The submission has already been approved. */
    record SubmissionAlreadyApproved(SubmissionId submissionId) implements ApproveSubmissionError { }
}
