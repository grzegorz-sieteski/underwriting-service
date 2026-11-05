package com.gs5.underwriting.submission.adapters.web;

import com.gs5.underwriting.submission.adapters.access.AccessRefusal;
import com.gs5.underwriting.submission.domain.ApproveSubmissionError;

/**
 * Every reason an approval request can be refused at the edge, folded in one place: the access
 * refusals and the domain refusals. The resource maps this union with a single exhaustive switch,
 * so a sixth reason added to either hierarchy fails to compile there (design section 19.3, D10).
 */
sealed interface ApproveRefusal {

    /** The caller was not admitted: not identified, or not permitted to approve (BR-01, BR-33). */
    record Access(AccessRefusal refusal) implements ApproveRefusal { }

    /** The caller was admitted, and the domain refused the approval (BR-35, BR-41, BR-36). */
    record Rejected(ApproveSubmissionError error) implements ApproveRefusal { }
}
