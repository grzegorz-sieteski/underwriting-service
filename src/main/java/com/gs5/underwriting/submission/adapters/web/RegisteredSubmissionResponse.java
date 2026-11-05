package com.gs5.underwriting.submission.adapters.web;

/**
 * The {@code 201 Created} body: the submission number of the submission that now exists. The
 * internal identity is never returned (BR-47).
 */
record RegisteredSubmissionResponse(String submissionNumber) { }
