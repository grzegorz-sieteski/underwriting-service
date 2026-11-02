package com.example.service.submission.adapters.web;

import module java.base;

import org.jspecify.annotations.Nullable;

/**
 * The register request body, and nothing more: the organisation, the registering caller and the
 * registration moment are never accepted as input (BR-15).
 *
 * <p><strong>No Bean Validation annotations.</strong> {@code spring-boot-starter-validation} is on
 * the classpath, but {@code @NotNull} / {@code @Positive} / {@code @Digits} would intercept BR-04,
 * BR-10, BR-11 and BR-12 and answer with a framework-shaped 400, bypassing the named refusal
 * reasons and making four of the eight error variants unreachable. Business inputs are validated
 * in the aggregate.
 *
 * <p><strong>{@code insuredLimit} must be {@code BigDecimal}.</strong> Any {@code double} or
 * {@code float} here destroys the scale and makes BR-12 unenforceable, and nothing in the pipeline
 * normalises it afterwards.
 */
record RegisterSubmissionRequest(@Nullable String externalRef,
                                 @Nullable BigDecimal insuredLimit) { }
