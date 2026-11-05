package com.gs5.underwriting.submission.adapters.persistence;

import module java.base;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

interface SubmissionApprovalJdbcRepository extends Repository<SubmissionApprovalJdbcEntity, UUID> {

    @Modifying
    @Query("""
            INSERT INTO submission_approvals
                (submission_id, tenant_id, approved_by, approved_at, version)
            VALUES
                (:submissionId, :tenantId, :approvedBy, :approvedAt, 0)
            ON CONFLICT (submission_id) DO NOTHING
            """)
    int insertIfNotApproved(@Param("submissionId") UUID submissionId,
                            @Param("tenantId") UUID tenantId,
                            @Param("approvedBy") String approvedBy,
                            @Param("approvedAt") Instant approvedAt);
}
