package com.gs5.underwriting.submission.adapters.persistence;

import module java.base;

import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * The {@code submissions} row, as Spring Data JDBC sees it.
 *
 * <p><strong>{@code @Version @Nullable Long version} is not optional.</strong> The submission id
 * is a UUID generated in the domain, so it is never null and Spring Data JDBC's "is this entity
 * new?" test cannot use it. Without a {@code @Version} property, {@code save()} on a brand-new
 * aggregate issues an <em>UPDATE that affects zero rows</em>: no exception, no row, and the caller
 * is told it succeeded. With it, {@code null} means insert and non-null means update under an
 * optimistic lock. The same trap applies to every aggregate root in this codebase.
 */
@Table("submissions")
record SubmissionJdbcEntity(
        @Id UUID id,
        @Column("tenant_id") UUID tenantId,
        @Column("submission_number") String submissionNumber,
        @Column("insured_limit") BigDecimal insuredLimit,
        @Column("registered_by") String registeredBy,
        @Column("registered_at") Instant registeredAt,
        @Version @Nullable Long version) {

    /** Names the row, never the amount or the registering caller (BR-22, OPEN-01). */
    @Override
    public String toString() {
        return "SubmissionJdbcEntity[id=" + id + ", tenantId=" + tenantId
                + ", submissionNumber=" + submissionNumber + ", version=" + version + "]";
    }
}
