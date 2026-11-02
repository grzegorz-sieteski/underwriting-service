package com.example.service.submission.query;

import module java.base;

/**
 * One row of a search result: the four fields the specification lists — reference, insured limit,
 * registering caller, registration moment — plus the submission's identity.
 *
 * <p>The id is confirmed by the product owner: search is the only way to discover a submission's
 * identity, and future operations need it to name a submission.
 *
 * <p>This record holds the insured limit (BR-22) and the registering caller (OPEN-01), so it masks
 * both when written to a log — and only there: the caller still receives both, because a
 * serialiser works through the record accessors.
 */
public record SubmissionSummaryView(UUID submissionId,
                                    String externalRef,
                                    BigDecimal insuredLimit,
                                    String registeredBy,
                                    Instant registeredAt) {

    private static final String MASKED = "***";

    @Override
    public String toString() {
        return "SubmissionSummaryView[submissionId=" + submissionId
                + ", externalRef=" + externalRef
                + ", insuredLimit=" + MASKED
                + ", registeredBy=" + MASKED
                + ", registeredAt=" + registeredAt + "]";
    }
}
