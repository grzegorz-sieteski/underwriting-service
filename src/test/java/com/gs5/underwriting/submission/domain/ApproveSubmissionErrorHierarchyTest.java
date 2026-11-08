package com.gs5.underwriting.submission.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The approval refusals are exactly the three the specification and the design name. This is
 * checked on the compiled hierarchy, not on a fixture, so a variant added or missing anywhere fails
 * here. Written as names so it compiles before the missing variant exists (D4).
 */
class ApproveSubmissionErrorHierarchyTest {

    @Test
    @DisplayName("SC-62: approval refuses for exactly three reasons: not found, approver registered the submission, already approved (BR-35, BR-41, BR-36)")
    void shouldPermitExactlyTheThreeApprovalRefusals() {
        assertThat(ApproveSubmissionError.class.getPermittedSubclasses())
                .extracting(Class::getSimpleName)
                .containsExactlyInAnyOrder("SubmissionNotFound", "ApproverRegisteredSubmission",
                        "SubmissionAlreadyApproved");
    }
}
