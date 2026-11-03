package com.gs5.underwriting.submission.domain;

import module java.base;

/** A request to approve an existing submission. */
public record ApproveSubmissionCommand(SubmissionId submissionId) { }
