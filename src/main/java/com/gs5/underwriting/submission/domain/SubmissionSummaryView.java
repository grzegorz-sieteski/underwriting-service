package com.gs5.underwriting.submission.domain;

import module java.base;

import com.gs5.underwriting.shared.kernel.LogSafeText;

/**
 * One row of a search result: the four fields the specification lists — submission number, insured
 * limit, registering caller, registration moment.
 *
 * <p>The internal identity is not on the row. A caller identifies a submission by its submission
 * number alone, and the identity never leaves the service (BR-47).
 *
 * <p>This record holds the insured limit (BR-22) and the registering caller (OPEN-01), so it masks
 * both when written to a log — and only there: the caller still receives both, because a
 * serialiser works through the record accessors.
 */
public record SubmissionSummaryView(String submissionNumber,
                                    BigDecimal insuredLimit,
                                    String registeredBy,
                                    Instant registeredAt,
                                    SubmissionState state) {

    private static final String MASKED = "***";

    @Override
    public String toString() {
        return "SubmissionSummaryView[submissionNumber="
                + LogSafeText.escapeControlCharacters(submissionNumber)
                + ", insuredLimit=" + MASKED
                + ", registeredBy=" + MASKED
                + ", registeredAt=" + registeredAt
                + ", state=" + state + "]";
    }
}
