package com.gs5.underwriting.submission.adapters.web;

import module java.base;

import java.util.UUID;

/** The body returned after approving a submission. */
record ApprovedSubmissionResponse(UUID submissionId) { }
