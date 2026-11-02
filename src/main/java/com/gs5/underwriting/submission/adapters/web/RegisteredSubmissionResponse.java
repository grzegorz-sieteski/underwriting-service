package com.gs5.underwriting.submission.adapters.web;

import module java.base;

/**
 * The {@code 201 Created} body: the identity of the submission that now exists, which is what the
 * caller needs to name it later.
 */
record RegisteredSubmissionResponse(UUID submissionId) { }
