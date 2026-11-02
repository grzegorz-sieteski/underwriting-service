package com.example.service.submission.query;

import module java.base;

import org.jspecify.annotations.Nullable;

/**
 * What the read port is asked to match: the tenant predicate plus the five optional filters.
 *
 * <p>{@code tenantId} lives inside the criteria and only the facade can build one. It is not a
 * filter the caller supplies and not something an adapter may forget: BR-17 is a business rule, so
 * the tenant predicate is part of the port's vocabulary.
 */
public record SubmissionSearchCriteria(UUID tenantId,
                                       @Nullable String externalRef,
                                       @Nullable BigDecimal insuredLimitFrom,
                                       @Nullable BigDecimal insuredLimitTo,
                                       @Nullable Instant registeredFrom,
                                       @Nullable Instant registeredTo) { }
