/**
 * Submission write side: the command facade, its command record, its refusal reasons and its
 * outbound ports.
 *
 * <p>Pure Java. The only dependencies permitted here are {@code java.*},
 * {@code com.gs5.underwriting.shared.kernel.*} and {@code org.jspecify.annotations.*} — which is
 * what lets every business rule in {@code docs/specs/submission-registration-spec.md} be tested
 * in milliseconds without a container.
 *
 * <p>The aggregate itself is deliberately absent from the contracts in this package: it is
 * package-private, so nothing outside this package can reach past
 * {@code SubmissionCommandFacade}.
 */
@NullMarked
package com.gs5.underwriting.submission.domain;

import org.jspecify.annotations.NullMarked;
