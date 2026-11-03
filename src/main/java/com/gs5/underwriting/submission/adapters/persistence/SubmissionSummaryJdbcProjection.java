package com.gs5.underwriting.submission.adapters.persistence;

import module java.base;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

record SubmissionSummaryJdbcProjection(UUID submissionId,
                                       String externalRef,
                                       BigDecimal insuredLimit,
                                       String registeredBy,
                                       Instant registeredAt) { }
