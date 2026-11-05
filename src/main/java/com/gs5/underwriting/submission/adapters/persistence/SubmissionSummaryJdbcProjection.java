package com.gs5.underwriting.submission.adapters.persistence;

import module java.base;

/**
 * One row of the search statement, as the database returns it. {@code approved} is true when an
 * approval is recorded for the submission (BR-45): a primitive is correct here, because the
 * projection is a row, not a domain value.
 */
record SubmissionSummaryJdbcProjection(UUID submissionId,
                                       String submissionNumber,
                                       BigDecimal insuredLimit,
                                       String registeredBy,
                                       Instant registeredAt,
                                       boolean approved) { }
