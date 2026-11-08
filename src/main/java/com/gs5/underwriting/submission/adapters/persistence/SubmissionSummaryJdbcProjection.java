package com.gs5.underwriting.submission.adapters.persistence;

import module java.base;

/**
 * One row of {@code submission_current}, as the search statement returns it. {@code approved} is
 * true when an approval is recorded for the submission (BR-45): a primitive is correct here,
 * because the projection is a row, not a domain value. It has no internal id, because the view
 * has none (BR-47).
 */
record SubmissionSummaryJdbcProjection(String submissionNumber,
                                       BigDecimal insuredLimit,
                                       String registeredBy,
                                       Instant registeredAt,
                                       boolean approved) { }
