package com.gs5.underwriting.submission.domain;

import com.gs5.underwriting.shared.kernel.Result;

/** A request to approve an existing submission, named by its submission number. */
public record ApproveSubmissionCommand(SubmissionNumber submissionNumber) {

    /**
     * Any text that cannot be a submission number names no submission (BR-48), so every parse
     * failure becomes the one refusal an unknown number gets (BR-35).
     */
    public static Result<ApproveSubmissionCommand, ApproveSubmissionError> parse(String rawNumber) {
        return SubmissionNumber.parse(rawNumber)
                .map(ApproveSubmissionCommand::new)
                .mapError(error -> new ApproveSubmissionError.SubmissionNotFound());
    }
}
