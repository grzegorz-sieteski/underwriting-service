package com.gs5.underwriting.submission.adapters.persistence;

import module java.base;

import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

/** The approval row for a submission. */
@Table("submission_approvals")
record SubmissionApprovalJdbcEntity(
        @Id @Column("submission_id") UUID submissionId,
        @Column("tenant_id") UUID tenantId,
        @Column("approved_by") String approvedBy,
        @Column("approved_at") Instant approvedAt,
        @Version @Nullable Long version) {

    /** Names the approval, never the approving caller (BR-42). */
    @Override
    public String toString() {
        return "SubmissionApprovalJdbcEntity[submissionId=" + submissionId + ", tenantId=" + tenantId
                + ", approvedAt=" + approvedAt + ", version=" + version + "]";
    }
}
